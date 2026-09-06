package sim

import api.ApiError
import api.ApiErrorCodes
import engine.GameClock
import engine.Travel
import knowledge.Deposits
import knowledge.DefaultPrices
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import model.Agent
import model.Construction
import model.ConstructionMaterial
import model.SupplyConstructionResponse
import model.Location
import model.ServerResets
import model.ServerStatus
import model.Shipyard
import model.WaypointTraitSymbol
import model.actions.Extraction
import model.contract.Contract
import model.responsebody.ChartResponse
import model.responsebody.ChartTransaction
import model.responsebody.ContractResponse
import model.responsebody.DeliverResponse
import model.responsebody.SiphonResponse
import model.actions.Survey
import model.actions.SurveyDeposit
import model.actions.SurveySize
import model.actions.Yield
import model.market.ActivityLevel
import model.market.Market
import model.market.MarketTradeGood
import model.market.MarketTransaction
import model.market.ShipyardTransaction
import model.market.SupplyLevel
import model.market.TradeGoodType
import model.market.TradeSymbol
import model.market.TransactionType
import model.responsebody.BuySellCargoResponse
import model.responsebody.ExtractionResponse
import model.responsebody.NavigationResponse
import model.responsebody.RefuelResponse
import model.responsebody.ShipPurchaseResponse
import model.responsebody.SurveyResponse
import model.ship.Cooldown
import model.ship.Crew
import model.ship.FlightMode
import model.ship.Navigation
import model.ship.PurchasableShip
import model.ship.Route
import model.ship.Ship
import model.ship.ShipNavStatus
import model.ship.ShipRole
import model.ship.ShipType
import model.ship.components.Cargo
import model.ship.components.Fuel
import model.ship.components.FuelConsumed
import model.ship.components.Registration
import model.system.System
import model.system.Waypoint
import model.system.WaypointModifier
import model.system.WaypointModifiers
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A system in a box. Answers the same calls as the server, with the same error codes, using the
 * formulas from the wiki and the assumptions in [SimRules]. Time comes from [clock], so it runs
 * on virtual time in tests and at any speed in the dashboard. All entry points are synchronized:
 * several behaviours may drive it at once.
 */
class SimUniverse(
    seed: SimSeed,
    val clock: GameClock,
    val rules: SimRules = SimRules(),
    randomSeed: Long = 1,
) {
    private val random = Random(randomSeed)
    private val lock = Any()

    val resetDate: String = seed.resetDate
    val system: System = seed.system
    val waypoints: MutableMap<String, Waypoint> = seed.waypoints.associateBy { it.symbol }.toMutableMap()

    /** Seeded ships start landed and off cooldown: the seed's timestamps belong to another clock. */
    val ships: MutableMap<String, Ship> = seed.ships.associate { it.symbol to landed(it, clock.now()) }.toMutableMap()
    var agent: Agent = seed.agent
        private set
    val shipyards: Map<String, Shipyard> = seed.shipyards.associateBy { it.symbol }
    /**
     * Exchange-equivalent base prices per good from every market in the seed that showed prices,
     * so an unvisited market is priced like its neighbours rather than from a flat guess.
     */
    private val observedBases: Map<TradeSymbol, Double> = seed.markets.flatMap { m -> m.tradeGoods.map { g -> g.symbol to g.sellPrice / DefaultPrices.typeFactor(g.type) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.average() }

    val markets: Map<String, SimMarket> = seed.markets.associate { it.symbol to SimMarket.from(it, rules, clock.now(), observedBases) }

    private val surveys = mutableMapOf<String, SimSurvey>()
    private val contractBook = mutableMapOf<String, Contract>()
    private val sites: MutableMap<String, Construction> = seed.waypoints.filter { it.isUnderConstruction }
        .associate { it.symbol to Construction(it.symbol, rules.constructionBill.map { (g, n) -> ConstructionMaterial(g, n, 0) }, false) }.toMutableMap()
    private var contractsIssued = 0
    private val asteroids = mutableMapOf<String, AsteroidState>()
    private val hq: Waypoint? = waypoints[seed.agent.headquarters]

    /** Calls answered, for budget assertions. */
    var calls: Int = 0
        private set

    val transactions = mutableListOf<MarketTransaction>()
    val extractions = mutableListOf<Triple<String, TradeSymbol, Int>>()

    val startingCredits: Long = seed.agent.credits

    // Reads

    fun status(): ServerStatus = counted { ServerStatus(status = "ok", version = "sim", resetDate = resetDate, serverResets = ServerResets("", "weekly")) }

    fun agent(): Agent = counted { agent }

    fun system(symbol: String): System = counted {
        if (symbol != system.symbol) throw error(404, 404, "Unknown system $symbol")
        system
    }

    fun listWaypoints(systemSymbol: String): List<Waypoint> = counted {
        waypoints.values.filter { it.systemSymbol == systemSymbol }.sortedBy { it.symbol }
    }

    fun listShips(): List<Ship> = counted { ships.keys.sorted().map { settle(it) } }

    fun ship(symbol: String): Ship = counted { settle(symbol) }

    fun market(waypoint: String): Market = counted {
        val market = markets[waypoint] ?: throw error(404, 404, "$waypoint has no market")
        market.view(clock.now(), visible = shipPresentAt(waypoint))
    }

    fun shipyard(waypoint: String): Shipyard = counted {
        val yard = shipyards[waypoint] ?: throw error(404, 404, "$waypoint has no shipyard")
        if (shipPresentAt(waypoint)) yard else yard.copy(ships = emptyList())
    }

    // Ship actions

    fun orbit(symbol: String): NavigationResponse = counted {
        val ship = settle(symbol)
        if (ship.nav.status == ShipNavStatus.IN_TRANSIT) throw error(400, ApiErrorCodes.SHIP_IN_TRANSIT, "$symbol is in transit")
        put(ship.copy(nav = ship.nav.copy(status = ShipNavStatus.IN_ORBIT))).let { NavigationResponse(it.nav, it.fuel) }
    }

    fun dock(symbol: String): NavigationResponse = counted {
        val ship = settle(symbol)
        if (ship.nav.status == ShipNavStatus.IN_TRANSIT) throw error(400, ApiErrorCodes.SHIP_IN_TRANSIT, "$symbol is in transit")
        put(ship.copy(nav = ship.nav.copy(status = ShipNavStatus.DOCKED))).let { NavigationResponse(it.nav, it.fuel) }
    }

    fun setFlightMode(symbol: String, mode: FlightMode): NavigationResponse = counted {
        val ship = settle(symbol)
        put(ship.copy(nav = ship.nav.copy(flightMode = mode))).let { NavigationResponse(it.nav, it.fuel) }
    }

    fun navigate(symbol: String, destination: String): NavigationResponse = counted {
        val ship = settle(symbol)
        when {
            ship.nav.status == ShipNavStatus.IN_TRANSIT -> throw error(400, ApiErrorCodes.NAVIGATE_IN_TRANSIT, "$symbol is in transit")
            ship.nav.status == ShipNavStatus.DOCKED -> throw error(400, ApiErrorCodes.SHIP_NOT_IN_ORBIT, "$symbol must be in orbit")
            ship.nav.waypointSymbol == destination -> throw error(400, ApiErrorCodes.NAVIGATE_SAME_DESTINATION, "$symbol is already at $destination")
        }
        val to = waypoints[destination] ?: throw error(400, 4201, "Unknown waypoint $destination")
        val from = waypoints.getValue(ship.nav.waypointSymbol)
        if (to.systemSymbol != from.systemSymbol) throw error(400, 4202, "$destination is outside the system")
        val distance = Travel.distance(from.x, from.y, to.x, to.y)
        val cost = if (ship.usesFuel) Travel.fuelCost(distance, ship.nav.flightMode) else 0L
        if (cost > ship.fuel.current) throw error(400, ApiErrorCodes.NAVIGATE_INSUFFICIENT_FUEL, "$symbol needs $cost fuel, has ${ship.fuel.current}")
        val now = clock.now()
        val seconds = Travel.seconds(distance, ship.nav.flightMode, ship.engine.speed)
        // Like the server, the ship's waypoint becomes the destination as soon as it departs.
        val nav = ship.nav.copy(
            status = ShipNavStatus.IN_TRANSIT,
            waypointSymbol = to.symbol,
            route = Route(origin = location(from), destination = location(to), arrival = now.plusSeconds(seconds), departureTime = now),
        )
        val fuel = ship.fuel.copy(current = ship.fuel.current - cost, consumed = FuelConsumed(cost, now.toString()))
        put(ship.copy(nav = nav, fuel = fuel)).let { NavigationResponse(it.nav, it.fuel) }
    }

    fun refuel(symbol: String, units: Int?): RefuelResponse = counted {
        val ship = settle(symbol)
        if (!ship.isDocked) throw error(400, ApiErrorCodes.SHIP_NOT_DOCKED, "$symbol must be docked")
        val market = markets[ship.nav.waypointSymbol] ?: throw error(400, ApiErrorCodes.MARKET_NOT_SOLD, "no market here")
        val fuelGood = market.goods[TradeSymbol.FUEL] ?: throw error(400, ApiErrorCodes.MARKET_NOT_SOLD, "no fuel sold here")
        val wanted = (units?.toLong() ?: (ship.fuel.capacity - ship.fuel.current)).coerceAtMost(ship.fuel.capacity - ship.fuel.current)
        val marketUnits = ceil(wanted.toDouble() / rules.fuelUnitsPerMarketUnit).toInt().coerceAtLeast(if (wanted > 0) 1 else 0)
        val now = clock.now()
        val price = fuelGood.purchasePrice(now)
        val total = price * marketUnits
        if (total > agent.credits) throw error(400, 4600, "insufficient credits")
        agent = agent.copy(credits = agent.credits - total)
        val fuel = ship.fuel.copy(current = (ship.fuel.current + marketUnits.toLong() * rules.fuelUnitsPerMarketUnit).coerceAtMost(ship.fuel.capacity))
        val transaction = MarketTransaction(symbol, ship.nav.waypointSymbol, TradeSymbol.FUEL, TransactionType.PURCHASE, marketUnits, price, total, now.toString())
        transactions += transaction
        val updated = put(ship.copy(fuel = fuel))
        RefuelResponse(agent, updated.fuel, transaction, updated.cargo)
    }

    fun sell(symbol: String, good: TradeSymbol, units: Int): BuySellCargoResponse = counted {
        val ship = settle(symbol)
        if (!ship.isDocked) throw error(400, ApiErrorCodes.SHIP_NOT_DOCKED, "$symbol must be docked")
        val market = markets[ship.nav.waypointSymbol] ?: throw error(400, ApiErrorCodes.MARKET_NOT_SOLD, "no market here")
        val line = market.goods[good] ?: throw error(400, ApiErrorCodes.MARKET_NOT_SOLD, "${market.symbol} does not trade $good")
        val have = ship.unitsOf(good)
        if (units <= 0 || units > have) throw error(400, 4219, "$symbol has $have $good, asked to sell $units")
        if (units > line.tradeVolume) throw error(400, ApiErrorCodes.MARKET_UNIT_LIMIT, "trade volume is ${line.tradeVolume}")
        val now = clock.now()
        val price = line.sellPrice(now)
        val total = price * units
        line.sold(units, now)
        agent = agent.copy(credits = agent.credits + total)
        val transaction = MarketTransaction(symbol, market.symbol, good, TransactionType.SELL, units, price, total, now.toString())
        transactions += transaction
        val updated = put(ship.copy(cargo = ship.cargo.adjusted(good, -units)))
        BuySellCargoResponse(agent, updated.cargo, transaction)
    }

    fun purchase(symbol: String, good: TradeSymbol, units: Int): BuySellCargoResponse = counted {
        val ship = settle(symbol)
        if (!ship.isDocked) throw error(400, ApiErrorCodes.SHIP_NOT_DOCKED, "$symbol must be docked")
        val market = markets[ship.nav.waypointSymbol] ?: throw error(400, ApiErrorCodes.MARKET_NOT_SOLD, "no market here")
        val line = market.goods[good] ?: throw error(400, 4601, "${market.symbol} does not sell $good")
        if (units <= 0) throw error(400, 4219, "units must be positive")
        if (units > line.tradeVolume) throw error(400, ApiErrorCodes.MARKET_UNIT_LIMIT, "trade volume is ${line.tradeVolume}")
        if (units > ship.cargoSpaceLeft) throw error(400, 4217, "$symbol has room for ${ship.cargoSpaceLeft}")
        val now = clock.now()
        val price = line.purchasePrice(now)
        val total = price * units
        if (total > agent.credits) throw error(400, 4600, "insufficient credits: need $total")
        line.bought(units, now)
        agent = agent.copy(credits = agent.credits - total)
        val transaction = MarketTransaction(symbol, market.symbol, good, TransactionType.PURCHASE, units, price, total, now.toString())
        transactions += transaction
        val updated = put(ship.copy(cargo = ship.cargo.adjusted(good, units)))
        BuySellCargoResponse(agent, updated.cargo, transaction)
    }

    fun jettison(symbol: String, good: TradeSymbol, units: Int): Cargo = counted {
        val ship = settle(symbol)
        val have = ship.unitsOf(good)
        if (units <= 0 || units > have) throw error(400, 4219, "$symbol has $have $good")
        put(ship.copy(cargo = ship.cargo.adjusted(good, -units))).cargo
    }

    /** Cargo between two of our ships: same waypoint, same docked-or-orbit state, room in the receiver. */
    fun transfer(fromSymbol: String, toSymbol: String, good: TradeSymbol, units: Int): model.responsebody.TransferResponse = counted {
        val from = settle(fromSymbol)
        val to = settle(toSymbol)
        if (from.nav.waypointSymbol != to.nav.waypointSymbol) throw error(400, 4234, "$fromSymbol and $toSymbol are not at the same waypoint")
        if (from.isDocked != to.isDocked) throw error(400, 4271, "$fromSymbol and $toSymbol must both be docked or both in orbit")
        val have = from.unitsOf(good)
        if (units <= 0 || units > have) throw error(400, 4219, "$fromSymbol has $have $good")
        if (units > to.cargoSpaceLeft) throw error(400, 4217, "$toSymbol has room for ${to.cargoSpaceLeft}")
        val after = put(from.copy(cargo = from.cargo.adjusted(good, -units)))
        val target = put(to.copy(cargo = to.cargo.adjusted(good, units)))
        model.responsebody.TransferResponse(after.cargo, target.cargo)
    }

    fun survey(symbol: String): SurveyResponse = counted {
        val ship = settle(symbol)
        if (!ship.isInOrbit) throw error(400, 4223, "$symbol must be in orbit")
        if (!ship.canSurvey) throw error(400, 4240, "$symbol has no surveyor")
        val here = waypoints.getValue(ship.nav.waypointSymbol)
        if (!here.isMineable) throw error(400, 4222, "${here.symbol} cannot be surveyed")
        checkCooldown(ship)
        val mix = Deposits.yieldMix(here.traitSymbols)
        if (mix.isEmpty()) throw error(400, ApiErrorCodes.WAYPOINT_NO_YIELD, "${here.symbol} has no deposits")
        val now = clock.now()
        val strength = ship.mounts.filter { it.symbol.name.startsWith("MOUNT_SURVEYOR") }.sumOf { it.strength }.toInt().coerceAtLeast(1)
        val count = 1 + random.nextInt(strength.coerceAtMost(2) + 1).coerceAtMost(2)
        val created = (1..count).map {
            val deposits = (1..(3 + random.nextInt(4))).map { SurveyDeposit(draw(mix)) }
            val size = when (random.nextInt(100)) { in 0..49 -> SurveySize.SMALL; in 50..84 -> SurveySize.MODERATE; else -> SurveySize.LARGE }
            val survey = Survey("${here.symbol}-${random.nextLong().toString(16).takeLast(8).uppercase()}", here.symbol, deposits, now.plusSeconds(rules.surveyLifetime.inWholeSeconds), size)
            surveys[survey.signature] = SimSurvey(survey, budgetFor(size))
            survey
        }
        val cooldown = cooldown(symbol, rules.surveyCooldown.inWholeSeconds, now)
        put(ship.copy(cooldown = cooldown))
        SurveyResponse(cooldown, created)
    }

    fun extract(symbol: String, survey: Survey?): ExtractionResponse = counted {
        val ship = settle(symbol)
        if (!ship.isInOrbit) throw error(400, ApiErrorCodes.SHIP_NOT_IN_ORBIT, "$symbol must be in orbit")
        if (!ship.canMine) throw error(400, 4243, "$symbol has no mining laser")
        val here = waypoints.getValue(ship.nav.waypointSymbol)
        if (!here.isMineable) throw error(400, 4205, "${here.symbol} cannot be mined")
        if (ship.cargoFull) throw error(400, ApiErrorCodes.CARGO_FULL, "$symbol's cargo is full")
        checkCooldown(ship)
        val now = clock.now()
        val state = asteroidState(here, now)
        if (here.hasTrait(WaypointTraitSymbol.STRIPPED) || state.stripped) throw error(400, ApiErrorCodes.WAYPOINT_NO_YIELD, "${here.symbol} is stripped")
        val mix = Deposits.yieldMix(here.traitSymbols)
        if (mix.isEmpty()) throw error(400, ApiErrorCodes.WAYPOINT_NO_YIELD, "${here.symbol} has no deposits")
        if (state.critical && random.nextDouble() < rules.criticalRefuseChance) throw error(400, ApiErrorCodes.EXTRACT_DESTABILIZED, "${here.symbol} is destabilized")

        var multiplier = when { state.critical -> rules.criticalYield; state.unstable -> rules.unstableYield; else -> 1.0 }
        val good: TradeSymbol
        if (survey != null) {
            val known = surveys[survey.signature] ?: throw error(400, 4220, "survey signature unknown")
            if (known.survey != survey) throw error(400, 4220, "survey does not match its signature")
            if (survey.symbol != here.symbol) throw error(400, 4270, "survey is for ${survey.symbol}")
            if (!survey.isValidAt(now)) throw error(400, ApiErrorCodes.SURVEY_EXPIRED, "survey expired")
            if (known.budget <= 0) throw error(400, ApiErrorCodes.SURVEY_EXHAUSTED, "survey exhausted")
            known.budget--
            good = survey.deposits[random.nextInt(survey.deposits.size)].symbol
            multiplier *= rules.surveyYieldBonus
        } else {
            good = draw(mix)
        }
        val strength = ship.miningStrength.toDouble()
        val raw = strength * (rules.yieldPerStrengthMin + random.nextDouble() * (rules.yieldPerStrengthMax - rules.yieldPerStrengthMin))
        val units = (raw * multiplier).roundToInt().coerceAtLeast(1).coerceAtMost(ship.cargoSpaceLeft)
        state.recent += 1.0
        val modifiers = modifiersOf(state)
        if (here.modifiers != modifiers) waypoints[here.symbol] = here.copy(modifiers = modifiers)
        extractions += Triple(here.symbol, good, units)
        val cooldown = cooldown(symbol, rules.extractCooldown.inWholeSeconds, now)
        val updated = put(ship.copy(cargo = ship.cargo.adjusted(good, units), cooldown = cooldown))
        ExtractionResponse(Extraction(symbol, Yield(good, units.toLong())), cooldown, updated.cargo, modifiers)
    }

    fun siphon(symbol: String): SiphonResponse = counted {
        val ship = settle(symbol)
        if (!ship.isInOrbit) throw error(400, ApiErrorCodes.SHIP_NOT_IN_ORBIT, "$symbol must be in orbit")
        if (!ship.canSiphon) throw error(400, 4258, "$symbol has no gas siphon")
        val here = waypoints.getValue(ship.nav.waypointSymbol)
        if (!here.isSiphonable) throw error(400, 4259, "${here.symbol} cannot be siphoned")
        if (ship.cargoFull) throw error(400, ApiErrorCodes.CARGO_FULL, "$symbol's cargo is full")
        checkCooldown(ship)
        val now = clock.now()
        val good = draw(Deposits.gasGiant)
        val strength = ship.siphonStrength.toDouble()
        val raw = strength * (rules.yieldPerStrengthMin + random.nextDouble() * (rules.yieldPerStrengthMax - rules.yieldPerStrengthMin))
        val units = raw.roundToInt().coerceAtLeast(1).coerceAtMost(ship.cargoSpaceLeft)
        extractions += Triple(here.symbol, good, units)
        val cooldown = cooldown(symbol, rules.extractCooldown.inWholeSeconds, now)
        val updated = put(ship.copy(cargo = ship.cargo.adjusted(good, units), cooldown = cooldown))
        SiphonResponse(Extraction(symbol, Yield(good, units.toLong())), cooldown, updated.cargo)
    }

    fun chart(symbol: String): ChartResponse = counted {
        val ship = settle(symbol)
        val here = waypoints.getValue(ship.nav.waypointSymbol)
        if (here.isCharted) throw error(400, 4230, "${here.symbol} is already charted")
        val now = clock.now()
        val reward = rules.chartReward
        val charted = here.copy(traits = here.traits.filterNot { it.symbol == WaypointTraitSymbol.UNCHARTED }, chart = model.system.Chart(agent.symbol, now.toString()))
        waypoints[here.symbol] = charted
        agent = agent.copy(credits = agent.credits + reward)
        ChartResponse(charted.chart!!, charted, ChartTransaction(here.symbol, symbol, reward, now.toString()), agent)
    }

    // Contracts: procurement of a good some market here imports, paid at a multiple of its sale value.

    fun listContracts(): List<Contract> = counted { contractBook.values.sortedBy { it.id } }

    fun negotiateContract(symbol: String): ContractResponse = counted {
        val ship = settle(symbol)
        val here = waypoints.getValue(ship.nav.waypointSymbol)
        if (here.faction == null) throw error(400, 4700, "${here.symbol} has no faction")
        if (contractBook.values.any { !it.fulfilled }) throw error(400, 4511, "an unfulfilled contract already exists")
        val now = clock.now()
        val candidates = markets.values.flatMap { m -> m.imports.map { g -> m.symbol to g } }.filter { (_, g) -> markets.values.any { it.symbol != it.symbol || true } }
        val (destination, good) = candidates[random.nextInt(candidates.size)]
        val units = 20 + random.nextInt(60)
        val value = markets.getValue(destination).goods.getValue(good).sellPrice(now).toLong() * units
        contractsIssued++
        val contract = Contract(
            id = "sim-contract-${contractsIssued.toString().padStart(4, '0')}",
            factionSymbol = here.faction!!.symbol.name,
            type = "PROCUREMENT",
            terms = model.contract.ContractTerms(
                deadline = now.plusSeconds(rules.contractDeadlineHours * 3600).toString(),
                payment = model.contract.PaymentTerm((value * rules.contractPayMultiple * 0.15).toLong(), (value * rules.contractPayMultiple * 0.85).toLong()),
                deliver = listOf(model.contract.DeliverTerm(good, destination, units.toLong(), 0)),
            ),
            accepted = false, fulfilled = false,
            expiration = now.plusSeconds(3600).toString(), deadlineToAccept = now.plusSeconds(3600).toString(),
        )
        contractBook[contract.id] = contract
        ContractResponse(contract, agent)
    }

    fun acceptContract(id: String): ContractResponse = counted {
        val c = contractBook[id] ?: throw error(404, 404, "no contract $id")
        if (c.accepted) throw error(400, 4501, "already accepted")
        val updated = c.copy(accepted = true)
        contractBook[id] = updated
        agent = agent.copy(credits = agent.credits + c.terms.payment.onAccepted)
        ContractResponse(updated, agent)
    }

    fun deliverContract(id: String, symbol: String, good: TradeSymbol, units: Int): DeliverResponse = counted {
        val c = contractBook[id] ?: throw error(404, 404, "no contract $id")
        if (!c.accepted) throw error(400, 4505, "not accepted")
        val ship = settle(symbol)
        val term = c.terms.deliver.firstOrNull { it.tradeSymbol == good } ?: throw error(400, 4508, "$good is not in the terms")
        if (ship.nav.waypointSymbol != term.destinationSymbol) throw error(400, 4510, "$symbol is not at ${term.destinationSymbol}")
        if (ship.unitsOf(good) < units || units <= 0) throw error(400, 4219, "$symbol has ${ship.unitsOf(good)} $good")
        val accepted = minOf(units.toLong(), term.unitsRequired - term.unitsFulfilled).toInt()
        val updated = c.copy(terms = c.terms.copy(deliver = c.terms.deliver.map { if (it.tradeSymbol == good) it.copy(unitsFulfilled = it.unitsFulfilled + accepted) else it }))
        contractBook[id] = updated
        val after = put(ship.copy(cargo = ship.cargo.adjusted(good, -accepted)))
        DeliverResponse(updated, after.cargo)
    }

    fun fulfillContract(id: String): ContractResponse = counted {
        val c = contractBook[id] ?: throw error(404, 404, "no contract $id")
        if (c.terms.deliver.any { it.unitsFulfilled < it.unitsRequired }) throw error(400, 4502, "delivery incomplete")
        val updated = c.copy(fulfilled = true)
        contractBook[id] = updated
        agent = agent.copy(credits = agent.credits + c.terms.payment.onFulfilled)
        ContractResponse(updated, agent)
    }

    fun construction(waypoint: String): Construction = counted { sites[waypoint] ?: throw error(404, 404, "$waypoint is not under construction") }

    fun supplyConstruction(waypoint: String, symbol: String, good: TradeSymbol, units: Int): SupplyConstructionResponse = counted {
        val site = sites[waypoint] ?: throw error(400, 4802, "$waypoint is not under construction")
        val ship = settle(symbol)
        if (ship.nav.waypointSymbol != waypoint) throw error(400, 4802, "$symbol is not at $waypoint")
        val material = site.materials.firstOrNull { it.tradeSymbol == good } ?: throw error(400, 4800, "$good is not required")
        if (material.fulfilled >= material.required) throw error(400, 4801, "$good is already fulfilled")
        if (units <= 0 || ship.unitsOf(good) < units) throw error(400, 4219, "$symbol has ${ship.unitsOf(good)} $good")
        val taken = minOf(units.toLong(), material.required - material.fulfilled).toInt()
        val materials = site.materials.map { if (it.tradeSymbol == good) it.copy(fulfilled = it.fulfilled + taken) else it }
        val updated = Construction(waypoint, materials, materials.all { it.fulfilled >= it.required })
        sites[waypoint] = updated
        if (updated.isComplete) waypoints[waypoint]?.let { waypoints[waypoint] = it.copy(isUnderConstruction = false) }
        val after = put(ship.copy(cargo = ship.cargo.adjusted(good, -taken)))
        SupplyConstructionResponse(updated, after.cargo)
    }

    fun purchaseShip(type: ShipType, waypoint: String): ShipPurchaseResponse = counted {
        val yard = shipyards[waypoint] ?: throw error(400, 4245, "$waypoint has no shipyard")
        if (!shipPresentAt(waypoint)) throw error(400, 4245, "no ship of yours is at $waypoint")
        val template = yard.ships.firstOrNull { it.type == type } ?: throw error(400, 4605, "$waypoint does not sell $type")
        if (template.purchasePrice > agent.credits) throw error(400, ApiErrorCodes.PURCHASE_SHIP_CREDITS, "insufficient credits")
        val now = clock.now()
        agent = agent.copy(credits = agent.credits - template.purchasePrice, shipCount = agent.shipCount + 1)
        val ship = newShip(template, waypoints.getValue(waypoint), now)
        ships[ship.symbol] = ship
        val transaction = ShipyardTransaction(waypoint, ship.symbol, type, template.purchasePrice.toInt(), agent.symbol, now)
        ShipPurchaseResponse(agent, ship, transaction)
    }

    // Reporting

    fun creditsEarned(): Long = agent.credits - startingCredits

    fun asteroidReport(): Map<String, String> = asteroids.mapValues { (_, s) -> "recent=${"%.0f".format(s.recent)} ${modifiersOf(s).joinToString(",") { it.symbol }}" }

    // Internals

    private fun <T> counted(block: () -> T): T = synchronized(lock) { calls++; block() }

    private fun error(status: Int, code: Int, message: String, data: JsonObject? = null): ApiError = ApiError(status, code, message, "sim", data = data)

    private fun put(ship: Ship): Ship { ships[ship.symbol] = ship; return ship }

    /** Applies an overdue arrival. */
    private fun settle(symbol: String): Ship {
        val ship = ships[symbol] ?: throw error(404, 404, "Unknown ship $symbol")
        val now = clock.now()
        if (ship.nav.status == ShipNavStatus.IN_TRANSIT && !ship.nav.route.arrival.isAfter(now)) {
            return put(ship.copy(nav = ship.nav.copy(status = ShipNavStatus.IN_ORBIT, waypointSymbol = ship.nav.route.destination.symbol)))
        }
        return ship
    }

    private fun shipPresentAt(waypoint: String): Boolean =
        ships.keys.any { val s = settle(it); s.nav.waypointSymbol == waypoint && s.nav.status != ShipNavStatus.IN_TRANSIT }

    private fun checkCooldown(ship: Ship) {
        val now = clock.now()
        val until = ship.cooldown.expiresAt(now) ?: return
        val remaining = until.epochSecond - now.epochSecond
        throw error(409, ApiErrorCodes.COOLDOWN_CONFLICT, "${ship.symbol} is on cooldown for ${remaining}s", buildJsonObject {
            put("cooldown", buildJsonObject {
                put("shipSymbol", ship.symbol)
                put("totalSeconds", ship.cooldown.totalSeconds)
                put("remainingSeconds", remaining)
                put("expiration", JsonPrimitive(until.toString()))
            })
        })
    }

    private fun cooldown(ship: String, seconds: Long, now: Instant) = Cooldown(ship, seconds, seconds, now.plusSeconds(seconds))

    private fun location(w: Waypoint) = Location(w.symbol, w.type.name, w.systemSymbol, w.x.toLong(), w.y.toLong())

    private fun draw(mix: Map<TradeSymbol, Double>): TradeSymbol {
        var r = random.nextDouble() * mix.values.sum()
        for ((good, weight) in mix) {
            r -= weight
            if (r <= 0) return good
        }
        return mix.keys.last()
    }

    private fun budgetFor(size: SurveySize) = when (size) {
        SurveySize.SMALL -> rules.surveyBudgetSmall
        SurveySize.MODERATE -> rules.surveyBudgetModerate
        SurveySize.LARGE -> rules.surveyBudgetLarge
    }

    private fun asteroidState(waypoint: Waypoint, now: Instant): AsteroidState {
        val state = asteroids.getOrPut(waypoint.symbol) {
            val crowded = hq != null && Travel.distance(hq.x, hq.y, waypoint.x, waypoint.y) <= rules.crowdedRadius
            AsteroidState(0.0, now, if (crowded) rules.foreignExtractionsPerHourNearHq else 0.0)
        }
        val hours = (now.toEpochMilli() - state.updatedAt.toEpochMilli()) / 3_600_000.0
        if (hours > 0) {
            state.recent = (state.recent + hours * state.foreignPerHour - hours * rules.recoveryPerHour).coerceAtLeast(0.0)
            state.updatedAt = now
        }
        if (state.recent >= rules.strippedAfter) state.stripped = true
        return state
    }

    private fun modifiersOf(state: AsteroidState): List<WaypointModifier> = buildList {
        if (state.stripped) add(WaypointModifiers.of(WaypointModifiers.STRIPPED))
        if (state.critical) add(WaypointModifiers.of(WaypointModifiers.CRITICAL_LIMIT))
        if (state.unstable) add(WaypointModifiers.of(WaypointModifiers.UNSTABLE))
    }

    private inner class AsteroidState(var recent: Double, var updatedAt: Instant, val foreignPerHour: Double) {
        var stripped = false
        val unstable: Boolean get() = recent >= rules.unstableAfter
        val critical: Boolean get() = recent >= rules.criticalAfter
    }

    private class SimSurvey(val survey: Survey, var budget: Int)

    private fun newShip(template: PurchasableShip, at: Waypoint, now: Instant): Ship {
        val symbol = "${agent.symbol}-${(ships.size + 1).toString(16).uppercase()}"
        val cargoCapacity = template.modules.filter { it.symbol.startsWith("MODULE_CARGO_HOLD") }.sumOf { it.capacity }.toInt()
        val role = when (template.type) {
            ShipType.SHIP_MINING_DRONE, ShipType.SHIP_ORE_HOUND -> ShipRole.EXCAVATOR
            ShipType.SHIP_SURVEYOR -> ShipRole.SURVEYOR
            ShipType.SHIP_PROBE -> ShipRole.SATELLITE
            ShipType.SHIP_LIGHT_HAULER, ShipType.SHIP_HEAVY_FREIGHTER, ShipType.SHIP_BULK_FREIGHTER, ShipType.SHIP_LIGHT_SHUTTLE -> ShipRole.HAULER
            else -> ShipRole.COMMAND
        }
        return Ship(
            symbol = symbol,
            nav = Navigation(at.systemSymbol, at.symbol, Route(origin = location(at), destination = location(at), arrival = now, departureTime = now), ShipNavStatus.DOCKED, FlightMode.CRUISE),
            crew = template.crew,
            fuel = Fuel(template.frame.fuelCapacity, template.frame.fuelCapacity),
            cooldown = Cooldown(symbol, 0, 0),
            frame = template.frame,
            engine = template.engine,
            reactor = template.reactor,
            modules = template.modules,
            mounts = template.mounts,
            registration = Registration(symbol, agent.startingFaction, role),
            cargo = Cargo(cargoCapacity, 0),
        )
    }

    companion object {
        /** Builds a probe's crew record for templates that lack one. */
        val NO_CREW = Crew(0, 0, 0)

        /** The ship at its destination, in orbit if it was flying, with no cooldown, as of [now]. */
        fun landed(ship: Ship, now: Instant): Ship {
            val at = if (ship.nav.status == ShipNavStatus.IN_TRANSIT) ship.nav.route.destination else ship.nav.route.destination.takeIf { it.symbol == ship.nav.waypointSymbol } ?: ship.nav.route.origin
            val status = if (ship.nav.status == ShipNavStatus.IN_TRANSIT) ShipNavStatus.IN_ORBIT else ship.nav.status
            return ship.copy(
                nav = ship.nav.copy(status = status, waypointSymbol = at.symbol, route = ship.nav.route.copy(origin = at, destination = at, arrival = now, departureTime = now)),
                cooldown = Cooldown(ship.symbol, 0, 0),
            )
        }
    }
}

/** One market's live prices. Selling pushes a good's price down; time pulls it back up. */
class SimMarket private constructor(
    val symbol: String,
    val imports: List<TradeSymbol>,
    val exports: List<TradeSymbol>,
    val exchange: List<TradeSymbol>,
    val goods: Map<TradeSymbol, SimGood>,
    private val source: Market,
) {
    fun view(now: Instant, visible: Boolean): Market = source.copy(
        tradeGoods = if (visible) goods.values.sortedBy { it.symbol.name }.map { it.asTradeGood(now) } else emptyList(),
    ).also { it.lastRead = now }

    companion object {
        fun from(market: Market, rules: SimRules, now: Instant, observedBases: Map<TradeSymbol, Double> = emptyMap()): SimMarket {
            val goods = mutableMapOf<TradeSymbol, SimGood>()
            fun add(symbol: TradeSymbol, type: TradeGoodType) {
                val seen = market.good(symbol)
                val base = observedBases[symbol]
                val sell = seen?.sellPrice?.toDouble() ?: base?.let { it * DefaultPrices.typeFactor(type) } ?: DefaultPrices.sell(symbol, type).toDouble()
                val purchase = seen?.purchasePrice?.toDouble() ?: (sell * DefaultPrices.purchaseFactor(type))
                goods[symbol] = SimGood(
                    symbol, type,
                    tradeVolume = seen?.tradeVolume ?: (if (symbol == TradeSymbol.FUEL) DefaultPrices.volume(symbol) else rules.defaultTradeVolume.coerceAtLeast(1)),
                    baseSell = sell.coerceAtLeast(1.0),
                    basePurchase = purchase.coerceAtLeast(2.0),
                    rules = rules, updatedAt = now,
                    seedSupply = seen?.supply ?: SupplyLevel.MODERATE,
                    seedActivity = seen?.activity ?: if (type == TradeGoodType.EXCHANGE) null else ActivityLevel.WEAK,
                )
            }
            market.imports.forEach { add(it.symbol, TradeGoodType.IMPORT) }
            market.exports.forEach { add(it.symbol, TradeGoodType.EXPORT) }
            market.exchange.forEach { add(it.symbol, TradeGoodType.EXCHANGE) }
            // An export's production depends on the inputs this market imports (knowledge.ImportMap).
            val importSymbols = market.imports.map { it.symbol }
            goods.values.filter { it.type == TradeGoodType.EXPORT }.forEach { export ->
                export.inputs = knowledge.ImportMap.inputsOf(export.symbol, importSymbols).mapNotNull { goods[it] }
            }
            return SimMarket(market.symbol, market.imports.map { it.symbol }, market.exports.map { it.symbol }, market.exchange.map { it.symbol }, goods, market.copy(tradeGoods = emptyList()))
        }
    }
}

class SimGood(
    val symbol: TradeSymbol,
    val type: TradeGoodType,
    val tradeVolume: Int,
    private val baseSell: Double,
    private val basePurchase: Double,
    private val rules: SimRules,
    private var updatedAt: Instant,
    /** The stock and activity the seed showed; the levels move from here as the price moves. */
    private val seedSupply: SupplyLevel = SupplyLevel.MODERATE,
    private val seedActivity: ActivityLevel? = ActivityLevel.WEAK,
) {
    /** For an export: the inputs its market imports. Production is constrained while any is short. */
    var inputs: List<SimGood> = emptyList()

    /** 1.0 is the seeded price; selling pushes it down, buying up, time pulls it back. */
    private var pressure = 1.0

    /** Stock reads one level lower for every [SimRules.supplyLevelPerPriceRatio] the price sits above the seed, one higher below it. */
    fun supply(now: Instant): SupplyLevel {
        recover(now)
        val shift = Math.round(Math.log(pressure) / Math.log(rules.supplyLevelPerPriceRatio)).toInt()
        val levels = SupplyLevel.entries
        return levels[(seedSupply.ordinal - shift).coerceIn(0, levels.size - 1)]
    }

    /** Whether this export's inputs are short (LIMITED or worse), which is what RESTRICTED means. */
    private fun inputsShort(now: Instant): Boolean = inputs.any { it.supply(now) <= SupplyLevel.LIMITED }
    private fun inputsFed(now: Instant): Boolean = inputs.isNotEmpty() && inputs.all { it.supply(now) >= SupplyLevel.HIGH }

    fun activity(now: Instant): ActivityLevel? = when (type) {
        TradeGoodType.EXCHANGE -> null
        TradeGoodType.EXPORT -> when {
            inputsShort(now) -> ActivityLevel.RESTRICTED
            recentBought >= rules.growingAfterVolumes -> ActivityLevel.GROWING
            else -> seedActivity ?: ActivityLevel.WEAK
        }
        TradeGoodType.IMPORT -> if (recentSold >= rules.growingAfterVolumes) ActivityLevel.GROWING else seedActivity ?: ActivityLevel.WEAK
    }

    /** Volumes we have recently sold and bought here; each one makes the next hit harder. Decays with recovery. */
    private var recentSold = 0.0
    private var recentBought = 0.0

    /** Drifts back toward 1.0 from either side as time passes; the recent-volume counters fade with it. */
    private fun recover(now: Instant) {
        val hours = (now.toEpochMilli() - updatedAt.toEpochMilli()) / 3_600_000.0
        if (hours > 0) {
            // A producer short of inputs barely recovers; one whose inputs are stocked recovers faster (the official page's rule).
            val factor = if (type == TradeGoodType.EXPORT && inputs.isNotEmpty()) when {
                inputs.any { it.supplyQuiet() <= SupplyLevel.LIMITED } -> rules.restrictedRecoveryFactor
                inputs.all { it.supplyQuiet() >= SupplyLevel.HIGH } -> rules.fedRecoveryFactor
                else -> 1.0
            } else 1.0
            val step = hours * rules.priceRecoveryPerHour * factor
            val target = if (type == TradeGoodType.EXPORT && inputs.isNotEmpty() && inputs.all { it.supplyQuiet() >= SupplyLevel.HIGH }) rules.fedExportTarget else 1.0
            pressure = if (pressure < target) (pressure + step).coerceAtMost(target) else (pressure - step).coerceAtLeast(target)
            val fade = Math.pow(0.5, hours) // half of the memory gone per hour
            recentSold *= fade
            recentBought *= fade
            updatedAt = now
        }
    }

    /** Buying from a market pushes its price up, harder with every volume. */
    fun bought(units: Int, now: Instant) {
        recover(now)
        val volumes = units.toDouble() / tradeVolume
        val rise = rules.buyImpactPerVolume * Math.pow(rules.buyImpactGrowth, recentBought) * volumes
        pressure = (pressure * (1 + rise)).coerceAtMost(rules.priceCeiling)
        recentBought += volumes
    }

    fun sellPrice(now: Instant): Int { recover(now); return (baseSell * pressure).roundToInt().coerceAtLeast(1) }
    fun purchasePrice(now: Instant): Int { recover(now); return (basePurchase * pressure).roundToInt().coerceAtLeast(2) }

    /** Selling to a market pushes its price down, harder with every volume. */
    fun sold(units: Int, now: Instant) {
        recover(now)
        val volumes = units.toDouble() / tradeVolume
        val drop = rules.sellImpactPerVolume * Math.pow(rules.sellImpactGrowth, recentSold) * volumes
        pressure = (pressure * (1 - drop).coerceAtLeast(0.05)).coerceAtLeast(rules.priceFloor)
        recentSold += volumes
    }

    /** The stock level without touching the clock; used while another good is recovering. */
    private fun supplyQuiet(): SupplyLevel {
        val shift = Math.round(Math.log(pressure) / Math.log(rules.supplyLevelPerPriceRatio)).toInt()
        return SupplyLevel.entries[(seedSupply.ordinal - shift).coerceIn(0, SupplyLevel.entries.size - 1)]
    }
    private fun pressureNow(): Double = pressure

    fun asTradeGood(now: Instant): MarketTradeGood {
        recover(now)
        return MarketTradeGood(symbol, type, tradeVolume, supply(now), purchasePrice(now), sellPrice(now), activity(now))
    }
}
