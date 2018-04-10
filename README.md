# Broadcast Message Service
Program to broadcast messages concurrently from one machine to all other machines in the cluster. </n>
The program starts by first taking as input a graph of nodes ( Each node is a machine in the cluster) and building a Spanning Tree to reduce the cost of communication between the nodes. </n>
The termination of the Spanning Tree is performed through a <em>converge cast</em> operation where the leaf node initiates the process of saying <em> I AM DONE </em> to its parent which then propagates the message all the way to the root of the spanning tree. <\n>
Upon termination of converge-cast operation, the broadcast phase begins where each machine sends out a message (In this case a random value generated as a function of exponential distribution. The sum of all the values are then calculated at the end to check if they are equal).
Multiple converge-cast operations are performed since all the nodes are participating in the broadcast operation.

## Scripts
* `./launcher 'config-name'` compiles and launches the program on all specified machines in the config file
* `./cleanup 'config-name'` to kill all processes running </n> deletes all log files and kills all running processes
