# Fuel Costs

Navigating between waypoints within a system or Warping between systems incurs a Fuel Cost.  The actual cost is based on distance traveled and the flight mode.

The formula for each Flight Mode is given below.  $d$ is the distance.  There is a minimum fuel cost of 1.

| Flight Mode| Fuel Cost |
| ---- | ---- |
|  CRUISE  | $round(d)$  | 
|  DRIFT   | $1$  | 
|   BURN   | $2 * round(d)$ (minimum cost of 2)| 
| STEALTH  | $round(d)$  | 

# Travel Time

Travel Time for navigating between waypoints within a system or warping between systems is based on distance traveled, the flight mode, and the engine speed.

The formula for travel time is given as $round(round(max(1,d))*{multiplier \over speed} + 15)$ where $d$ is the distance, $speed$ is taken from `ship.engine.speed`, and $multiplier$ is taken from the table below.  Note: if the distance is zero, round this to 1.  The result is given as seconds.  This means travel speed is constant over the distance, if ignoring the 15 second addition to every flight. Shorter flights are 'penalized' more with the 15 second addition being a larger proportion of the overall flight time.

In JavaScript this would be:
```javascript
Math.round(Math.round(Math.max(1, dist)) * (multiplier / engineSpeed) + 15)
```




|  Flight Mode| Navigation multiplier | Warp multiplier |
| ------- | --- | --- |
| CRUISE  | 25 | 50 | 
| DRIFT   | 250 | 300* |
| BURN    | 12.5 (CRUISE/2) | 25* |
| STEALTH | 30* | 60* |

Note: Values with `*` have not been confirmed on 2.1 or later.

Note: Warping requires a warp-drive.  Warp-drives have a maximum range given, although you are generally limited by the amount of fuel rather than the warp range.

# Jump Cooldown

Jumping between systems is instantaneous, but incurs a Cooldown.  Any ship can jump between systems where there is a Jump Gate connection at both ends.  Ships with Jump Drives are able to jump between systems without Jump Gates, although this requires Antimatter as Fuel.

The Jump Cooldown is calculated as $round(15 + 0.3 * d)$, where $d$ is the distance between the systems.

Note: you can Navigate or Warp straight away after Jumping - but you need to wait until your Cooldown has finished before jumping again.

# Calculating Distance 

The distance between systems and waypoints can be calculated using the Euclidean distance formula.  Calculate the distance between systems when Warping or Jumping, and the distance between waypoints when Navigating

The formula is given as 
$\sqrt{(x1-x2)^2 + (y1-y2)^2}$

Generally, in the travel time and fuel calculations, this value is always rounded before it is used.

_Notes: The above has been compiled from information supplied by our discord users_