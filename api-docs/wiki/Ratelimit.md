# Rate limits
Spacetraders has a rate limit that limits IP Addresses and Agents.

If an Agent is running from multiple IPs to circumvent the rate limit it wont increase the amount of requests that go through.  
The same goes for multiple agents from one IP, then they have to share the same IP rate limit.

The rate limiter uses two distinct pools - a static pool and a burst pool.

The static pool can handle up to 2 requests. If a request is made, a timer begins, and one second after the initial request, the pool refills to its maximum capacity of 2 requests, regardless of any subsequent requests within that second.

When the static pool has been exhausted (i.e., it has handled its maximum of 2 requests), any subsequent requests are consumed from the burst pool.

The burst pool can handle up to 30 requests. Similar to the static pool, if a request is made from this pool, a timer begins. After 60 seconds from the first request, the burst pool refills to its maximum capacity of 30 requests, irrespective of any further requests made within those 60 seconds.

If all requests exceed the capacity of both pools (i.e., more than 2 requests for the static pool and more than 30 for the burst pool within their respective refill periods), an error will occur.

## Pools
vars | static | burst | 
--- | --- | --- | 
 duration | 1 | 60 | 
 points | 2 | 30 |
 priority | x |  |

## Priority
The static pool gets prioritized if it has points to consume.

## Refill
*Duration* seconds **after the first consume of that pool**, the pool will be refilled to *points*
