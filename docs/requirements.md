Implement a multiplayer version of Conway's Game of Life: https://en.wikipedia.org/wiki/Conway%27s_Game_of_Life

 

1. The game should run one instance and multiple clients should be able to connect to it and observe the generation updates. Pick the communication protocol of your choice.
 

2. Support a large universe (e.g. 2^64 x 2^64). The universe should wrap on itself in both dimensions (think of a torus). The universe is mostly empty, except for the area with object(s) -- for example, Gosper's glider gun in the center.
 

3. There should be a simple UI with the possibility to interactively configure the initial state of the universe (100 x 100), start the algorithm, and observe the changes. The UI can be console based.
 

4. There should also be a function to store/load the state. Please prepare an example file with Gosper's glider gun.
 

5. You are expected to use AI agents for the task. Be prepared to discuss your approach and work process.