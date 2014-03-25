package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public interface HeuristicProvider
{
  double[] heuristicStateValue(ForwardDeadReckonInternalMachineState state,
                               TreeNode previousNode);

  int getSampleWeight();
}
