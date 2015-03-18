package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.player.gamer.statemachine.sancho.LocalRegionSearcher;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class ValidatedPlayoutMCTSSearchTree extends SearchTree
{
  private final LocalRegionSearcher localSearcher;

  public ValidatedPlayoutMCTSSearchTree(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    super(xiStateMachine);

    localSearcher = new LocalRegionSearcher(xiStateMachine, xiStateMachine.getRoleOrdering(), null, null);
  }

  @Override
  protected SearchTreeNode createRootTreeNode(ForwardDeadReckonInternalMachineState xiRootState)
  {
    return new ValidatedPlayoutMCTSSearchTreeNode(this, xiRootState, 0);
  }

  LocalRegionSearcher getLocalSearcher()
  {
    return localSearcher;
  }
}
