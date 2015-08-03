package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class BasicMCTSSearchTree extends SearchTree
{

  public BasicMCTSSearchTree(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    super(xiStateMachine);
  }

  @Override
  protected SearchTreeNode<BasicMCTSSearchTree> createRootTreeNode(ForwardDeadReckonInternalMachineState xiRootState)
  {
    return new BasicMCTSSearchTreeNode(this, xiRootState, 0);
  }
}
