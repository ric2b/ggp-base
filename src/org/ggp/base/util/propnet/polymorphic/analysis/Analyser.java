package org.ggp.base.util.propnet.polymorphic.analysis;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

public abstract class Analyser
{
	//	Initialize with a given a state machine to analyse
	public abstract void init(TestForwardDeadReckonPropnetStateMachine stateMachine);
	
	//	Accrue a state sample from each state within a rollout
	public abstract void accrueInterimStateSample(ForwardDeadReckonInternalMachineState state, int choosingRoleIndex);
	
	//	Accrue a rollout sample (terminal state from a rollout)
	public abstract void accrueTerminalStateSample(ForwardDeadReckonInternalMachineState state, double[] roleScores);
	
	//	Complete the analysis
	public abstract void completeAnalysis();
}
