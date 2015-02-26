package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class ARRSearchTree extends SearchTree
{

  boolean mSuppressBackProp = false;

  public ARRSearchTree(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    super(xiStateMachine);
  }

  @Override
  protected SearchTreeNode<ARRSearchTree> createRootTreeNode(ForwardDeadReckonInternalMachineState xiRootState)
  {
    return new ARRSearchTreeNode(this, xiRootState, 0);
  }

  @Override
  public void grow()
  {
    mSuppressBackProp = false;
    super.grow();
  }
}
