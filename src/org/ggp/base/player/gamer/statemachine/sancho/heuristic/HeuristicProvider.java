package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public interface HeuristicProvider
{
  double[] getHeuristicValue(ForwardDeadReckonInternalMachineState state,
                             ForwardDeadReckonInternalMachineState previousState);

  int getSampleWeight();
}
