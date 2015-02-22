package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MCTSPrimeSearchTree extends SearchTree
{

  public MCTSPrimeSearchTree(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    super(xiStateMachine);
  }

  @Override
  protected SearchTreeNode createRootTreeNode(ForwardDeadReckonInternalMachineState xiRootState)
  {
    return new MCTSPrimeSearchTreeNode(this, xiRootState, 0);
  }
}
