package bridge.fx

import bridge.canvas.Attr
import bridge.canvas.HalfCanvas
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette
import model.WaypointTraitSymbol
import model.system.Waypoint
import model.system.WaypointType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural portraits of waypoints, seeded by the waypoint's symbol so each always looks the
 * same, animated by [t] in seconds. Spheres are lit from the upper left, textured with noise in
 * a palette chosen from the waypoint's traits, and turn slowly; their clouds turn faster and
 * their atmosphere glows past the limb. Everything is drawn in half-cells so the shapes are round.
 */
object Art {
    /** Draws [wp] to fill [p], leaving a one-cell margin. */
    fun waypoint(p: Painter, wp: Waypoint, t: Double, extra: Extra = Extra()) {
        val w = p.width
        val h = p.height
        if (w < 8 || h < 4) return
        val seed = wp.symbol.hashCode().toLong()
        when (wp.type) {
            WaypointType.PLANET -> sphere(p, seed, t, planetSpec(wp))
            WaypointType.GAS_GIANT -> sphere(p, seed, t, gasGiantSpec(wp))
            WaypointType.MOON -> sphere(p, seed, t, moonSpec(wp))
            WaypointType.ASTEROID, WaypointType.ENGINEERED_ASTEROID, WaypointType.ASTEROID_BASE -> asteroid(p, seed, t, wp)
            WaypointType.ORBITAL_STATION -> station(p, seed, t)
            WaypointType.JUMP_GATE -> gate(p, seed, t, extra.gateFraction, wp.isUnderConstruction)
            WaypointType.FUEL_STATION -> fuelStation(p, seed, t)
            WaypointType.ASTEROID_FIELD, WaypointType.DEBRIS_FIELD -> field(p, seed, t, wp.type == WaypointType.DEBRIS_FIELD)
            WaypointType.NEBULA -> nebula(p, seed, t)
            WaypointType.GRAVITY_WELL, WaypointType.ARTIFICIAL_GRAVITY_WELL -> well(p, seed, t, wp.type == WaypointType.ARTIFICIAL_GRAVITY_WELL)
        }
    }

    /** Facts the art needs that are not on the waypoint itself. */
    data class Extra(val gateFraction: Double = 0.0)

    // ---- spheres -------------------------------------------------------------------------------

    /** How a sphere is textured. Colours are picked from [surface] by a noise value in 0..1 and the latitude in -1..1. */
    class SphereSpec(
        val surface: (noise: Double, lat: Double) -> Rgb,
        val detail: Double = 2.2,
        val spin: Double = 0.12,
        /** Differential rotation: bands at this latitude factor turn faster, as a gas giant's do. */
        val shear: Double = 0.0,
        val clouds: Double = 0.0,
        val cloudColour: Rgb = Rgb(236, 238, 244),
        val cloudSpin: Double = 2.4,
        val glow: Rgb? = null,
        val glowWidth: Double = 0.16,
        val craters: Boolean = false,
    )

    fun sphere(p: Painter, seed: Long, t: Double, spec: SphereSpec) {
        val canvas = HalfCanvas(p.width, p.height)
        val margin = if (spec.glow != null) 4 else 2
        val r = (minOf(canvas.width / 2.0, canvas.rows / 2.0) - margin).coerceAtLeast(2.0)
        // Rotation advances in half-pixel steps, so the texture is redrawn a few times a second, not every frame.
        val step = 0.5 / r
        val turn = Math.floor(t * spec.spin / step) * step
        val cloudTurn = Math.floor(t * spec.spin * spec.cloudSpin / step) * step
        val cx = canvas.width / 2.0
        val cy = canvas.rows / 2.0
        val noise = Noise(seed)
        val clouds = Noise(seed * 31 + 7)
        val lx = -0.55
        val ly = -0.35
        val lz = 0.76
        val glowOuter = r * (1 + spec.glowWidth)
        for (py in 0 until canvas.rows) for (px in 0 until canvas.width) {
            val nx = (px + 0.5 - cx) / r
            val ny = (py + 0.5 - cy) / r
            val d2 = nx * nx + ny * ny
            if (d2 > 1.0) {
                if (spec.glow != null) {
                    val d = sqrt(d2) * r
                    if (d < glowOuter) {
                        val f = 1 - (d - r) / (glowOuter - r)
                        canvas.blend(px, py, spec.glow, 0.55 * f * f)
                    }
                }
                continue
            }
            val nz = sqrt(1 - d2)
            // Turn about the vertical axis; bands shear when the spec asks for it.
            val a = turn * (1 + spec.shear * sin(ny * 3.1))
            val rx = nx * cos(a) + nz * sin(a)
            val rz = -nx * sin(a) + nz * cos(a)
            val n = noise.fbm(rx * spec.detail + 10, ny * spec.detail + 10, rz * spec.detail + 10)
            var colour = spec.surface(n, ny)
            if (spec.craters) {
                val c = noise.at(rx * 6 + 40, ny * 6 + 40, rz * 6 + 40)
                if (c > 0.74) colour = colour.scale(0.6) else if (c > 0.70) colour = colour.scale(1.15)
            }
            if (spec.clouds > 0) {
                val ca = cloudTurn
                val cxr = nx * cos(ca) + nz * sin(ca)
                val czr = -nx * sin(ca) + nz * cos(ca)
                val c = clouds.fbm(cxr * 2.6 + 3, ny * 2.6 + 3, czr * 2.6 + 3)
                val threshold = 1 - spec.clouds
                if (c > threshold) colour = colour.mix(spec.cloudColour, ((c - threshold) / (1 - threshold) * 1.6).coerceIn(0.0, 0.9))
            }
            val light = max(0.0, nx * lx + ny * ly + nz * lz)
            val shade = 0.22 + 0.78 * light
            // Quantised, so neighbouring cells share a style and a repaint costs fewer escape codes.
            canvas.set(px, py, colour.scale(shade).quantize())
        }
        canvas.paint(p, 0, 0)
    }

    private fun Waypoint.has(vararg traits: WaypointTraitSymbol) = traits.any { hasTrait(it) }

    fun planetSpec(wp: Waypoint): SphereSpec {
        val glow = when {
            wp.has(WaypointTraitSymbol.TOXIC_ATMOSPHERE) -> Rgb(120, 220, 90)
            wp.has(WaypointTraitSymbol.CORROSIVE_ATMOSPHERE) -> Rgb(230, 200, 70)
            wp.has(WaypointTraitSymbol.BREATHABLE_ATMOSPHERE) -> Rgb(120, 170, 255)
            wp.has(WaypointTraitSymbol.THIN_ATMOSPHERE) -> Rgb(140, 160, 200)
            wp.has(WaypointTraitSymbol.OCEAN, WaypointTraitSymbol.TEMPERATE, WaypointTraitSymbol.JUNGLE, WaypointTraitSymbol.SWAMP) -> Rgb(110, 160, 240)
            else -> null
        }
        val clouds = when {
            wp.has(WaypointTraitSymbol.PERPETUAL_OVERCAST, WaypointTraitSymbol.ASH_CLOUDS) -> 0.6
            wp.has(WaypointTraitSymbol.OCEAN, WaypointTraitSymbol.TEMPERATE, WaypointTraitSymbol.JUNGLE, WaypointTraitSymbol.SWAMP) -> 0.34
            wp.has(WaypointTraitSymbol.BARREN, WaypointTraitSymbol.ROCKY) && glow != null -> 0.15
            else -> 0.0
        }
        val cloudColour = if (wp.has(WaypointTraitSymbol.ASH_CLOUDS)) Rgb(90, 84, 80) else if (wp.has(WaypointTraitSymbol.TOXIC_ATMOSPHERE)) Rgb(190, 230, 160) else Rgb(236, 238, 244)
        val surface: (Double, Double) -> Rgb = when {
            wp.has(WaypointTraitSymbol.OCEAN) -> { n, lat ->
                val ice = abs(lat) > 0.86
                when {
                    ice -> Rgb(225, 235, 245)
                    n > 0.60 -> Rgb(70, 130, 60).mix(Rgb(120, 110, 70), (n - 0.6) * 4)
                    n > 0.56 -> Rgb(190, 180, 120)
                    else -> Rgb(22, 60, 150).mix(Rgb(40, 110, 190), n * 1.5)
                }
            }
            wp.has(WaypointTraitSymbol.VOLCANIC, WaypointTraitSymbol.MAGMA_SEAS, WaypointTraitSymbol.SUPERVOLCANOES) -> { n, _ ->
                if (n > 0.66) Rgb(240, 90, 30).mix(Rgb(255, 200, 60), (n - 0.66) * 3) else Rgb(50, 40, 40).mix(Rgb(110, 70, 60), n)
            }
            wp.has(WaypointTraitSymbol.FROZEN, WaypointTraitSymbol.ICE_CRYSTALS) -> { n, _ -> Rgb(200, 220, 240).mix(Rgb(120, 160, 210), n) }
            wp.has(WaypointTraitSymbol.JUNGLE, WaypointTraitSymbol.DIVERSE_LIFE) -> { n, _ -> if (n < 0.42) Rgb(30, 70, 130) else Rgb(30, 110, 50).mix(Rgb(90, 160, 60), n) }
            wp.has(WaypointTraitSymbol.SWAMP) -> { n, _ -> Rgb(60, 80, 50).mix(Rgb(110, 120, 60), n) }
            wp.has(WaypointTraitSymbol.TEMPERATE, WaypointTraitSymbol.TERRAFORMED) -> { n, _ -> if (n < 0.5) Rgb(30, 80, 160) else Rgb(60, 130, 60).mix(Rgb(140, 130, 90), (n - 0.5) * 2) }
            wp.has(WaypointTraitSymbol.RADIOACTIVE) -> { n, _ -> Rgb(90, 110, 40).mix(Rgb(180, 220, 60), n) }
            wp.has(WaypointTraitSymbol.BARREN, WaypointTraitSymbol.DRY_SEABEDS, WaypointTraitSymbol.SALT_FLATS) -> { n, _ -> Rgb(150, 130, 100).mix(Rgb(210, 200, 170), n) }
            wp.has(WaypointTraitSymbol.ROCKY, WaypointTraitSymbol.CANYONS) -> { n, _ -> Rgb(110, 90, 80).mix(Rgb(170, 140, 110), n) }
            else -> { n, _ -> Rgb(80, 100, 120).mix(Rgb(150, 160, 150), n) }
        }
        return SphereSpec(surface = surface, detail = 2.4, spin = 0.10, clouds = clouds, cloudColour = cloudColour, glow = glow)
    }

    fun gasGiantSpec(wp: Waypoint): SphereSpec {
        val (a, b) = when {
            wp.has(WaypointTraitSymbol.METHANE_POOLS) -> Rgb(70, 150, 160) to Rgb(190, 220, 220)
            wp.has(WaypointTraitSymbol.EXPLOSIVE_GASES) -> Rgb(200, 110, 60) to Rgb(240, 200, 130)
            wp.has(WaypointTraitSymbol.STRONG_MAGNETOSPHERE, WaypointTraitSymbol.VIBRANT_AURORAS) -> Rgb(90, 90, 180) to Rgb(190, 170, 230)
            else -> Rgb(200, 140, 80) to Rgb(240, 220, 180)
        }
        return SphereSpec(
            surface = { n, lat -> a.mix(b, 0.5 + 0.5 * sin(lat * 9 + n * 4)) },
            detail = 1.6,
            spin = 0.16,
            shear = 0.9,
            glow = a.mix(Rgb(255, 255, 255), 0.3),
            glowWidth = 0.1,
        )
    }

    fun moonSpec(wp: Waypoint): SphereSpec = SphereSpec(
        surface = { n, _ -> Rgb(120, 122, 130).mix(Rgb(190, 190, 195), n) },
        detail = 3.0,
        spin = 0.06,
        craters = true,
        glow = if (wp.has(WaypointTraitSymbol.THIN_ATMOSPHERE, WaypointTraitSymbol.BREATHABLE_ATMOSPHERE)) Rgb(140, 160, 200) else null,
        glowWidth = 0.08,
    )

    // ---- rocks ---------------------------------------------------------------------------------

    /** A tumbling lump: its outline is noise around a circle, lit from the upper left. */
    fun asteroid(p: Painter, seed: Long, t: Double, wp: Waypoint) {
        val canvas = HalfCanvas(p.width, p.height)
        val r = (minOf(canvas.width / 2.0, canvas.rows / 2.0) - 1).coerceAtLeast(2.0)
        val cx = canvas.width / 2.0
        val cy = canvas.rows / 2.0
        val noise = Noise(seed)
        val engineered = wp.type == WaypointType.ENGINEERED_ASTEROID
        val rough = if (engineered) 0.12 else 0.32
        val spin = if (engineered) 0.05 else 0.15
        val base = if (wp.has(WaypointTraitSymbol.PRECIOUS_METAL_DEPOSITS, WaypointTraitSymbol.RARE_METAL_DEPOSITS)) Rgb(150, 130, 90) else Rgb(110, 104, 98)
        for (py in 0 until canvas.rows) for (px in 0 until canvas.width) {
            val dx = px + 0.5 - cx
            val dy = py + 0.5 - cy
            val d = hypot(dx, dy)
            val angle = atan2(dy, dx) + t * spin
            val edge = r * (1 - rough + rough * noise.at(cos(angle) * 2 + 5, sin(angle) * 2 + 5, seed % 7 * 0.1))
            if (d > edge) continue
            val n = noise.fbm(dx / r * 3 + 20, dy / r * 3 + 20, t * spin)
            var colour = base.mix(base.scale(1.5), n)
            if (!engineered && noise.at(dx / r * 5 + 50, dy / r * 5 + 50, 0.0) > 0.78) colour = colour.scale(0.55)
            val nx = dx / edge
            val ny = dy / edge
            val nz = sqrt((1 - nx * nx - ny * ny).coerceAtLeast(0.0))
            val light = max(0.0, nx * -0.55 + ny * -0.35 + nz * 0.76)
            canvas.set(px, py, colour.scale(0.25 + 0.75 * light))
        }
        if (wp.type == WaypointType.ASTEROID_BASE || engineered) {
            // Lights of the base, blinking out of step.
            val lights = Noise(seed + 3)
            for (i in 0 until 6) {
                val a = i * 1.05 + 0.3
                val rr = r * 0.55
                val lx = (cx + cos(a) * rr).toInt()
                val ly = (cy + sin(a) * rr).toInt()
                val on = ((t * 1.3 + lights.at(i.toDouble(), 0.0) * 6) % 2.0) < 1.2
                if (canvas.get(lx, ly) != null) canvas.set(lx, ly, if (on) Rgb(255, 240, 200) else Rgb(120, 110, 90))
            }
        }
        canvas.paint(p, 0, 0)
    }

    // ---- structures ----------------------------------------------------------------------------

    /** A hub with two arms and panels, beacons blinking at the tips. Drawn in whole cells. */
    fun station(p: Painter, seed: Long, t: Double) {
        val w = p.width
        val h = p.height
        val cx = w / 2
        val cy = h / 2
        val hull = Rgb(150, 160, 185)
        val dark = hull.scale(0.6)
        val panel = Rgb(60, 90, 160)
        val armLen = ((w - 6) / 2).coerceAtLeast(3)
        // Arms
        for (x in cx - armLen..cx + armLen) p.put(x, cy, '═', dark)
        // Panels along the arms, hatched.
        val panelW = (armLen - 4).coerceAtLeast(2)
        for (side in listOf(-1, 1)) {
            val start = cx + side * 3
            for (i in 0 until panelW) {
                val x = start + side * i
                for (dy in listOf(-2, -1, 1, 2)) {
                    val y = cy + dy
                    if (y in 0 until h) p.put(x, y, if ((x + dy) % 2 == 0) '▒' else '░', panel, Palette.background)
                }
            }
        }
        // Hub
        for (dy in -1..1) for (dx in -2..2) p.put(cx + dx, cy + dy, '█', if (abs(dx) == 2 || abs(dy) == 1) dark else hull)
        p.put(cx, cy, '◉', Rgb(255, 230, 160), hull, Attr.BOLD)
        for (dy in listOf(-3, -2, 2, 3)) if (cy + dy in 0 until h) p.put(cx, cy + dy, '║', dark)
        // Beacons
        val on = (t * 1.5).toInt() % 2 == 0
        p.put(cx - armLen - 1, cy, if (on) '●' else '○', if (on) Rgb(255, 80, 80) else Rgb(120, 40, 40))
        p.put(cx + armLen + 1, cy, if (on) '○' else '●', if (on) Rgb(80, 200, 100) else Rgb(60, 240, 120))
        // Docking lights along the top and bottom, walking.
        val phase = (t * 4).toInt()
        for (i in -armLen..armLen step 3) {
            val lit = ((i + armLen) / 3 + phase) % 4 == 0
            if (cy - 3 >= 0) p.put(cx + i, cy - 3, '·', if (lit) Rgb(255, 255, 220) else Rgb(70, 75, 95))
            if (cy + 3 < h) p.put(cx + i, cy + 3, '·', if (lit) Rgb(255, 255, 220) else Rgb(70, 75, 95))
        }
    }

    /** A ring with a spark travelling round it; [fraction] of the construction bill is lit. */
    fun gate(p: Painter, seed: Long, t: Double, fraction: Double, underConstruction: Boolean) {
        val canvas = HalfCanvas(p.width, p.height)
        val r = (minOf(canvas.width / 2.0, canvas.rows / 2.0) - 2).coerceAtLeast(3.0)
        val cx = canvas.width / 2.0
        val cy = canvas.rows / 2.0
        val lit = Rgb(210, 120, 250)
        val dim = Rgb(70, 50, 100)
        val steps = (r * 8).toInt()
        val done = if (underConstruction) fraction.coerceIn(0.0, 1.0) else 1.0
        for (i in 0 until steps) {
            val f = i / steps.toDouble()
            val a = -PI / 2 + f * 2 * PI
            for (thick in listOf(0.0, 0.5)) {
                val x = (cx + cos(a) * (r - thick)).toInt()
                val y = (cy + sin(a) * (r - thick)).toInt()
                canvas.set(x, y, if (f < done) lit else dim)
            }
        }
        // Struts
        for (a in listOf(PI / 4, 3 * PI / 4, 5 * PI / 4, 7 * PI / 4)) {
            var rr = r * 0.75
            while (rr < r) {
                canvas.set((cx + cos(a) * rr).toInt(), (cy + sin(a) * rr).toInt(), Rgb(120, 110, 140))
                rr += 0.5
            }
        }
        // The spark, and the shimmer inside a finished gate.
        val sparkA = -PI / 2 + ((t * 0.5) % 1.0) * 2 * PI * done
        for (k in 0 until 6) {
            val a = sparkA - k * 0.06
            canvas.set((cx + cos(a) * r).toInt(), (cy + sin(a) * r).toInt(), Rgb(255, 240, 255).mix(lit, k / 6.0))
        }
        if (!underConstruction) {
            val noise = Noise(seed)
            for (py in 0 until canvas.rows) for (px in 0 until canvas.width) {
                val d = hypot(px + 0.5 - cx, py + 0.5 - cy) / r
                if (d < 0.9) {
                    val n = noise.fbm(px / 4.0 + t * 0.3, py / 4.0, t * 0.2)
                    if (n > 0.55) canvas.set(px, py, Palette.background.mix(Rgb(140, 90, 200), (n - 0.55) * 1.5 * (1 - d)))
                }
            }
        }
        canvas.paint(p, 0, 0)
        val label = if (underConstruction) "construction ${(done * 100).toInt()}%" else "open"
        p.textRight(p.width, p.height - 1, label, if (underConstruction) Palette.warn else Palette.good)
    }

    /** Tanks on a gantry, a beacon on top. */
    fun fuelStation(p: Painter, seed: Long, t: Double) {
        val w = p.width
        val h = p.height
        val cx = w / 2
        val cy = h / 2
        val tank = Rgb(210, 190, 110)
        val steel = Rgb(120, 125, 140)
        for (dx in listOf(-4, 0, 4)) {
            for (dy in -1..1) {
                p.put(cx + dx - 1, cy + dy, '▐', tank)
                p.put(cx + dx, cy + dy, '█', tank.scale(if (dy == 0) 1.0 else 0.8))
                p.put(cx + dx + 1, cy + dy, '▌', tank)
            }
            p.put(cx + dx, cy - 2, '╥', steel)
            p.put(cx + dx, cy + 2, '╨', steel)
        }
        for (x in cx - 6..cx + 6) {
            p.put(x, cy - 3, '─', steel)
            p.put(x, cy + 3, '─', steel)
        }
        val on = (t * 2).toInt() % 2 == 0
        p.put(cx, cy - 4, if (on) '●' else '○', Rgb(255, 210, 80))
        p.text(cx - 2, cy + 4, "FUEL", tank, null, Attr.BOLD)
    }

    /** Scattered rocks or wreckage drifting slowly. */
    fun field(p: Painter, seed: Long, t: Double, debris: Boolean) {
        val noise = Noise(seed)
        val glyphs = if (debris) "∙·˙∷⁚" else "∙·•◦"
        val base = if (debris) Rgb(140, 120, 100) else Rgb(120, 110, 100)
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val n = noise.at(x / 3.0 + t * 0.05, y / 2.0, 0.0)
            if (n > 0.72) {
                val g = glyphs[((n - 0.72) * 40).toInt().coerceIn(0, glyphs.length - 1)]
                p.put(x, y, g, Palette.background.mix(base, 0.4 + (n - 0.72) * 2))
            }
        }
    }

    /** Coloured gas, drifting. */
    fun nebula(p: Painter, seed: Long, t: Double) {
        val canvas = HalfCanvas(p.width, p.height)
        val noise = Noise(seed)
        val a = Rgb(180, 80, 160)
        val b = Rgb(80, 120, 220)
        for (py in 0 until canvas.rows) for (px in 0 until canvas.width) {
            val n = noise.fbm(px / 9.0 + t * 0.04, py / 9.0, t * 0.03, 4)
            if (n > 0.48) canvas.set(px, py, Palette.background.mix(a.mix(b, noise.at(px / 15.0, py / 15.0)), ((n - 0.48) * 2.2).coerceIn(0.0, 0.9)))
        }
        canvas.paint(p, 0, 0)
    }

    /** Rings falling inwards. */
    fun well(p: Painter, seed: Long, t: Double, artificial: Boolean) {
        val canvas = HalfCanvas(p.width, p.height)
        val r = (minOf(canvas.width / 2.0, canvas.rows / 2.0) - 1).coerceAtLeast(3.0)
        val cx = canvas.width / 2.0
        val cy = canvas.rows / 2.0
        val colour = if (artificial) Rgb(100, 220, 220) else Rgb(200, 204, 224)
        for (py in 0 until canvas.rows) for (px in 0 until canvas.width) {
            val d = hypot(px + 0.5 - cx, py + 0.5 - cy) / r
            if (d > 1.0) continue
            val ring = ((d * 6 - t * 0.8) % 1.0 + 1.0) % 1.0
            if (ring < 0.25) canvas.set(px, py, Palette.background.mix(colour, (1 - d) * (1 - ring * 4)))
        }
        canvas.set(cx.toInt(), cy.toInt(), Rgb(0, 0, 0))
        canvas.paint(p, 0, 0)
    }
}
