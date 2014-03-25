package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public interface HeuristicProvider
{
  double[] heuristicStateValue(ForwardDeadReckonInternalMachineState state,
                               ForwardDeadReckonInternalMachineState previousState);

  int getSampleWeight();
}
