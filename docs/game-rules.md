The Game of Life, also known as Conway's Game of Life (sometimes abbreviated as CGoL) or simply Life, is a cellular automaton devised by the British mathematician John Horton Conway in 1970.[1] It is a zero-player game,[2] meaning that its evolution is determined by its initial state, requiring no further input. One interacts with the Game of Life by creating an initial configuration and observing how it evolves in discrete time steps called generations. There is no limit to the number of generations (computational resources permitting) nor is there a win condition.

The Game of Life is played on an infinite square grid, with each cell being in one of two states: live or dead. Cells in configurations called patterns evolve over generations according to the number of live and dead cells in their Moore neighborhood, that is, the eight cells in their immediate proximity.

Patterns can be grouped into different types, such as still lifes, oscillators, and spaceships.

The Game of Life was first simulated manually, with computerized simulations arriving soon afterwards. Nowadays, more modern programs such as Golly are used. These programs often do not store cells as two-dimensional arrays, instead using algorithms such as Hashlife which represent patterns as a tree structure.

The Game of Life has spawned a number of other cellular automata, known as Life-like cellular automata. Examples include Highlife and Seeds. Other variations may include more than two states or use a non-square grid.

Rules
The universe of the Game of Life is an infinite, two-dimensional orthogonal grid of square cells, each of which is in one of two possible states, live or dead (or populated and unpopulated, respectively). Every cell interacts with its eight neighbours (its Moore neighborhood), which are the cells that are horizontally, vertically, or diagonally adjacent. At each step in time, the following transitions occur:

Any live cell with fewer than two live neighbours dies, as if by underpopulation.
Any live cell with two or three live neighbours lives on to the next generation.
Any live cell with more than three live neighbours dies, as if by overpopulation.
Any dead cell with exactly three live neighbours becomes a live cell, as if by reproduction.[3][4]: 3 
This makes it described by the rulestring B3/S23,[nb 1] where the numbers before the slash signify conditions for dead cells becoming alive, and the ones after it signifying survival conditions for cells that are already alive.[4]: 5 

The initial pattern constitutes the seed of the system. The first generation is created by applying the above rules simultaneously to every cell in the seed, live or dead; births and deaths occur simultaneously, and the discrete moment at which this happens is sometimes called a tick.[nb 2] Each generation is a pure function of the preceding one. The rules continue to be applied repeatedly to create further generations.

In the editable widget below, one of the patterns discovered by Conway, the I-heptomino can be simulated.