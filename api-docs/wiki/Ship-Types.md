# Ship Types

The following ship types are known 

| Symbol                  | Name               | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | Frame                 | Reactor            | Engine          | Mounts                                              | Modules                                                                                 |
| ----------------------- | ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------- | ------------------ | --------------- | --------------------------------------------------- | --------------------------------------------------------------------------------------- |
| SHIP_PROBE              | Probe Satellite    | A small, unmanned spacecraft that can be launched into orbit to gather data and perform basic tasks.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Frame Probe           | Solar Reactor I    | Impulse Drive I | -                                                   | -                                                                                       |
| SHIP_MINING_DRONE       | Mining Drone       | A small, unmanned spacecraft that can be used for mining operations, such as extracting valuable minerals from asteroids.                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Frame Drone           | Chemical Reactor I | Impulse Drive I | Mining Laser I x1                                   | Cargo Hold x1, Mineral Processor x1                                                     |
| SHIP_INTERCEPTOR        | Interceptor        | A small, agile spacecraft designed for high-speed, short-range combat missions.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | Frame Interceptor     | Chemical Reactor I | Ion Drive I     | Rotary Cannon x1, Missile Launcher x1               | Crew Quarters x1                                                                        |
| SHIP_LIGHT_HAULER       | Light Hauler       | A small, fast cargo ship that is designed for short-range transport of light loads.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | Frame Light Freighter | Chemical Reactor I | Ion Drive I     | Mount Surveyor I x1                                 | Cargo Hold x4, Crew Quarters x2                                                         |
| SHIP_COMMAND_FRIGATE    | Command Frigate    | A medium-sized warship that is designed for command and control operations and can be fitted with a variety of weapons and other systems.                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Frame Frigate         | Fission Reactor I  | Ion Drive II    | Sensor Array I x1, Mining Laser I x1, Surveyor I x1 | Cargo Hold x2, Crew Quarters x2, Mineral Processor x1, Jump Drive I x1, Warp Drive I x1 |
| SHIP_EXPLORER           | Explorer           | A large, long-range spacecraft designed for deep space exploration and scientific research.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | Frame Explorer        | Fusion Reactor I   | Ion Drive II    | Sensor Array II x1, Laser Cannon x1                 | Cargo Hold x1, Crew Quarters x2, Science Lab x1, Warp Drive I x1, Shield Generator x1   |
| SHIP_HEAVY_FREIGHTER    | Heavy Freighter    | The heavy freighter is a massive spacecraft designed for carrying large amounts of cargo across long distances. With its reinforced hull and advanced propulsion systems, the freighter is capable of navigating through harsh environments and hazardous conditions. It is equipped with a jump drive for faster-than-light travel, as well as a range of modules and mounts for handling a variety of cargo and defensive needs. The heavy freighter is a reliable and indispensable vessel for traders and haulers looking to transport goods and resources across the vast expanse of space. | Frame Heavy Freighter | Fusion Reactor I   | Ion Drive II    | Rotary Cannon x3                                    | Cargo Hold x6, Crew Quarters x4, Warp Drive II x1, Advanced Shield Generator x1         |
| SHIP_LIGHT_SHUTTLE      | Light Shuttle      | The light shuttle is a versatile spacecraft designed for exploration, diplomacy, and light cargo transport. With its compact size and agile maneuverability, the shuttle is capable of navigating through tight spaces and challenging environments.                                                                                                                                                                                                                                                                                                                                             | Frame Shuttle         | Chemical Reactor I | Impulse Drive I | Rotary Cannon x1                                    | Cargo Hold x1, Crew Quarters x1, Passenger Cabin x1, Envoy Quarters x1                  |
| SHIP_ORE_HOUND          | Ore Hound          | The Ore Hound is a specialized mining ship designed for extracting valuable ores and minerals from asteroids and other celestial bodies. With its advanced mining lasers and reinforced hull, the Ore Hound is capable of excavating large amounts of ore and minerals from even the toughest asteroids. It is equipped with a range of modules and mounts for handling a variety of mining and defensive needs, and is an essential vessel for miners and traders looking to profit from the rich resources of the galaxy.                                                                      | Frame Miner           | Fission Reactor I  | Ion Drive I     | Mining Laser II x1, Surveyor I x1                   | Cargo Hold x2, Mineral Processor x1, Crew Quarters x1                                   |
| SHIP_REFINING_FREIGHTER | Refining Freighter | A large cargo ship designed specifically for refining raw materials. Equipped with a powerful reactor and space for large modules, the refining freighter is a versatile and convenient tool for industrial operations in remote or difficult-to-reach locations.                                                                                                                                                                                                                                                                                                                                | Frame Heavy Freighter | Fusion Reactor I   | Ion Drive II    | Rotary Cannon x2, Missile Launcher x1               | Cargo Hold x4, Crew Quarters x4, Ore Refinery x1                                        |


# Shipyard Listings (seen in starting system on 2023-11-19)

```json
[
    {
        "type": "SHIP_PROBE",
        "name": "Probe Satellite",
        "description": "A small, unmanned spacecraft that can be launched into orbit to gather data and perform basic tasks.",
        "frame": {
            "symbol": "FRAME_PROBE",
            "name": "Probe",
            "description": "A small, unmanned spacecraft used for exploration, reconnaissance, and scientific research.",
            "moduleSlots": 0,
            "mountingPoints": 0,
            "fuelCapacity": 0,
            "requirements": {
                "power": 1,
                "crew": 0
            }
        },
        "reactor": {
            "symbol": "REACTOR_SOLAR_I",
            "name": "Solar Reactor I",
            "description": "A basic solar power reactor, used to generate electricity from solar energy.",
            "powerOutput": 3,
            "requirements": {
                "crew": 0
            }
        },
        "engine": {
            "symbol": "ENGINE_IMPULSE_DRIVE_I",
            "name": "Impulse Drive I",
            "description": "A basic low-energy propulsion system that generates thrust for interplanetary travel.",
            "speed": 3,
            "requirements": {
                "power": 1,
                "crew": 0
            }
        },
        "modules": [],
        "mounts": [],
        "crew": {
            "required": 0,
            "capacity": 0
        }
    },
    {
        "type": "SHIP_LIGHT_SHUTTLE",
        "name": "Light Shuttle",
        "description": "The light shuttle is a versatile spacecraft designed for exploration, diplomacy, and light cargo transport. With its compact size and agile maneuverability, the shuttle is capable of navigating through tight spaces and challenging environments.",
        "frame": {
            "symbol": "FRAME_SHUTTLE",
            "name": "Shuttle",
            "description": "A small, reusable spacecraft designed for short-range, low-speed travel between spacecraft or planetary surfaces.",
            "moduleSlots": 3,
            "mountingPoints": 1,
            "fuelCapacity": 300,
            "requirements": {
                "power": 1,
                "crew": 10
            }
        },
        "reactor": {
            "symbol": "REACTOR_CHEMICAL_I",
            "name": "Chemical Reactor I",
            "description": "A basic chemical power reactor, used to generate electricity from chemical reactions.",
            "powerOutput": 15,
            "requirements": {
                "crew": 3
            }
        },
        "engine": {
            "symbol": "ENGINE_IMPULSE_DRIVE_I",
            "name": "Impulse Drive I",
            "description": "A basic low-energy propulsion system that generates thrust for interplanetary travel.",
            "speed": 3,
            "requirements": {
                "power": 1,
                "crew": 0
            }
        },
        "modules": [
            {
                "symbol": "MODULE_CARGO_HOLD_II",
                "name": "Expanded Cargo Hold",
                "description": "An expanded cargo hold module that provides more efficient storage space for a ship's cargo.",
                "capacity": 40,
                "requirements": {
                    "crew": 2,
                    "power": 2,
                    "slots": 2
                }
            },
            {
                "symbol": "MODULE_CREW_QUARTERS_I",
                "name": "Crew Quarters",
                "description": "A module that provides living space and amenities for the crew.",
                "capacity": 40,
                "requirements": {
                    "crew": 2,
                    "power": 1,
                    "slots": 1
                }
            }
        ],
        "mounts": [
            {
                "symbol": "MOUNT_TURRET_I",
                "name": "Rotary Cannon",
                "description": "A rotary cannon is a type of mounted turret that is designed to fire a high volume of rounds in rapid succession.",
                "requirements": {
                    "power": 1,
                    "crew": 1
                }
            }
        ],
        "crew": {
            "required": 18,
            "capacity": 40
        }
    },
    {
        "type": "SHIP_LIGHT_HAULER",
        "name": "Light Hauler",
        "description": "A small, fast cargo ship that is designed for short-range transport of light loads.",
        "supply": "ABUNDANT",
        "purchasePrice": 243720,
        "frame": {
            "symbol": "FRAME_LIGHT_FREIGHTER",
            "name": "Light Freighter",
            "description": "A small, versatile spacecraft used for cargo transport and other commercial operations.",
            "moduleSlots": 6,
            "mountingPoints": 1,
            "fuelCapacity": 600,
            "requirements": {
                "power": 5,
                "crew": 40
            }
        },
        "reactor": {
            "symbol": "REACTOR_CHEMICAL_I",
            "name": "Chemical Reactor I",
            "description": "A basic chemical power reactor, used to generate electricity from chemical reactions.",
            "powerOutput": 15,
            "requirements": {
                "crew": 3
            }
        },
        "engine": {
            "symbol": "ENGINE_ION_DRIVE_I",
            "name": "Ion Drive I",
            "description": "An advanced propulsion system that uses ionized particles to generate high-speed, low-thrust acceleration.",
            "speed": 10,
            "requirements": {
                "power": 3,
                "crew": 3
            }
        },
        "modules": [
            {
                "symbol": "MODULE_CARGO_HOLD_II",
                "name": "Expanded Cargo Hold",
                "description": "An expanded cargo hold module that provides more efficient storage space for a ship's cargo.",
                "capacity": 40,
                "requirements": {
                    "crew": 2,
                    "power": 2,
                    "slots": 2
                }
            },
            {
                "symbol": "MODULE_CARGO_HOLD_II",
                "name": "Expanded Cargo Hold",
                "description": "An expanded cargo hold module that provides more efficient storage space for a ship's cargo.",
                "capacity": 40,
                "requirements": {
                    "crew": 2,
                    "power": 2,
                    "slots": 2
                }
            },
            {
                "symbol": "MODULE_CREW_QUARTERS_I",
                "name": "Crew Quarters",
                "description": "A module that provides living space and amenities for the crew.",
                "capacity": 40,
                "requirements": {
                    "crew": 2,
                    "power": 1,
                    "slots": 1
                }
            },
            {
                "symbol": "MODULE_CREW_QUARTERS_I",
                "name": "Crew Quarters",
                "description": "A module that provides living space and amenities for the crew.",
                "capacity": 40,
                "requirements": {
                    "crew": 2,
                    "power": 1,
                    "slots": 1
                }
            }
        ],
        "mounts": [
            {
                "symbol": "MOUNT_TURRET_I",
                "name": "Rotary Cannon",
                "description": "A rotary cannon is a type of mounted turret that is designed to fire a high volume of rounds in rapid succession.",
                "requirements": {
                    "power": 1,
                    "crew": 1
                }
            }
        ],
        "crew": {
            "required": 55,
            "capacity": 80
        }
    },
    {
        "type": "SHIP_SIPHON_DRONE",
        "name": "Mining Drone",
        "description": "A small, unmanned spacecraft that can be used for siphoning operations, such as extracting valuable gases from gas giants.",
        "supply": "ABUNDANT",
        "purchasePrice": 34899,
        "frame": {
            "symbol": "FRAME_DRONE",
            "name": "Drone",
            "description": "A small, unmanned spacecraft used for various tasks, such as surveillance, transportation, or combat.",
            "moduleSlots": 3,
            "mountingPoints": 2,
            "fuelCapacity": 80,
            "requirements": {
                "power": 1,
                "crew": -4
            }
        },
        "reactor": {
            "symbol": "REACTOR_CHEMICAL_I",
            "name": "Chemical Reactor I",
            "description": "A basic chemical power reactor, used to generate electricity from chemical reactions.",
            "powerOutput": 15,
            "requirements": {
                "crew": 3
            }
        },
        "engine": {
            "symbol": "ENGINE_IMPULSE_DRIVE_I",
            "name": "Impulse Drive I",
            "description": "A basic low-energy propulsion system that generates thrust for interplanetary travel.",
            "speed": 3,
            "requirements": {
                "power": 1,
                "crew": 0
            }
        },
        "modules": [
            {
                "symbol": "MODULE_CARGO_HOLD_I",
                "name": "Cargo Hold",
                "description": "A module that increases a ship's cargo capacity.",
                "capacity": 15,
                "requirements": {
                    "crew": 0,
                    "power": 1,
                    "slots": 1
                }
            },
            {
                "symbol": "MODULE_GAS_PROCESSOR_I",
                "name": "Gas Processor",
                "description": "Filters and processes extracted gases into their component parts, filters out impurities, and containerizes them into raw storage units.",
                "requirements": {
                    "crew": 0,
                    "power": 1,
                    "slots": 2
                }
            }
        ],
        "mounts": [
            {
                "symbol": "MOUNT_GAS_SIPHON_I",
                "name": "Gas Siphon I",
                "description": "A basic gas siphon that can extract gas from gas giants and other gas-rich bodies.",
                "strength": 10,
                "requirements": {
                    "crew": 0,
                    "power": 1
                }
            }
        ],
        "crew": {
            "required": -1,
            "capacity": 0
        }
    },
    {
        "type": "SHIP_MINING_DRONE",
        "name": "Mining Drone",
        "description": "A small, unmanned spacecraft that can be used for mining operations, such as extracting valuable minerals from asteroids.",
        "supply": "ABUNDANT",
        "purchasePrice": 38727,
        "frame": {
            "symbol": "FRAME_DRONE",
            "name": "Drone",
            "description": "A small, unmanned spacecraft used for various tasks, such as surveillance, transportation, or combat.",
            "moduleSlots": 3,
            "mountingPoints": 2,
            "fuelCapacity": 80,
            "requirements": {
                "power": 1,
                "crew": -4
            }
        },
        "reactor": {
            "symbol": "REACTOR_CHEMICAL_I",
            "name": "Chemical Reactor I",
            "description": "A basic chemical power reactor, used to generate electricity from chemical reactions.",
            "powerOutput": 15,
            "requirements": {
                "crew": 3
            }
        },
        "engine": {
            "symbol": "ENGINE_IMPULSE_DRIVE_I",
            "name": "Impulse Drive I",
            "description": "A basic low-energy propulsion system that generates thrust for interplanetary travel.",
            "speed": 3,
            "requirements": {
                "power": 1,
                "crew": 0
            }
        },
        "modules": [
            {
                "symbol": "MODULE_CARGO_HOLD_I",
                "name": "Cargo Hold",
                "description": "A module that increases a ship's cargo capacity.",
                "capacity": 15,
                "requirements": {
                    "crew": 0,
                    "power": 1,
                    "slots": 1
                }
            },
            {
                "symbol": "MODULE_MINERAL_PROCESSOR_I",
                "name": "Mineral Processor",
                "description": "Crushes and processes extracted minerals and ores into their component parts, filters out impurities, and containerizes them into raw storage units.",
                "requirements": {
                    "crew": 0,
                    "power": 1,
                    "slots": 2
                }
            }
        ],
        "mounts": [
            {
                "symbol": "MOUNT_MINING_LASER_I",
                "name": "Mining Laser I",
                "description": "A basic mining laser that can be used to extract valuable minerals from asteroids and other space objects.",
                "strength": 3,
                "requirements": {
                    "crew": 1,
                    "power": 1
                }
            }
        ],
        "crew": {
            "required": 0,
            "capacity": 0
        }
    },
    {
        "type": "SHIP_SURVEYOR",
        "name": "Surveyor Craft",
        "description": "A specialized spacecraft equipped with surveying mounts, designed for detailed surveying of celestial bodies, resource identification, and scientific research.",
        "supply": "ABUNDANT",
        "purchasePrice": 27504,
        "frame": {
            "symbol": "FRAME_DRONE",
            "name": "Drone",
            "description": "A small, unmanned spacecraft used for various tasks, such as surveillance, transportation, or combat.",
            "moduleSlots": 3,
            "mountingPoints": 2,
            "fuelCapacity": 80,
            "requirements": {
                "power": 1,
                "crew": -4
            }
        },
        "reactor": {
            "symbol": "REACTOR_CHEMICAL_I",
            "name": "Chemical Reactor I",
            "description": "A basic chemical power reactor, used to generate electricity from chemical reactions.",
            "powerOutput": 15,
            "requirements": {
                "crew": 3
            }
        },
        "engine": {
            "symbol": "ENGINE_IMPULSE_DRIVE_I",
            "name": "Impulse Drive I",
            "description": "A basic low-energy propulsion system that generates thrust for interplanetary travel.",
            "speed": 3,
            "requirements": {
                "power": 1,
                "crew": 0
            }
        },
        "modules": [],
        "mounts": [
            {
                "symbol": "MOUNT_SURVEYOR_I",
                "name": "Surveyor I",
                "description": "A basic survey probe that can be used to gather information about a mineral deposit.",
                "strength": 1,
                "deposits": [
                    "QUARTZ_SAND",
                    "SILICON_CRYSTALS",
                    "PRECIOUS_STONES",
                    "ICE_WATER",
                    "AMMONIA_ICE",
                    "IRON_ORE",
                    "COPPER_ORE",
                    "SILVER_ORE",
                    "ALUMINUM_ORE",
                    "GOLD_ORE",
                    "PLATINUM_ORE"
                ],
                "requirements": {
                    "crew": 1,
                    "power": 1
                }
            }
        ],
        "crew": {
            "required": 0,
            "capacity": 0
        }
    }
]
```
