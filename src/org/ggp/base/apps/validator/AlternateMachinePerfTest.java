// AlternateMachinePerfTest.java
package org.ggp.base.apps.validator;

import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.StateMachine;

public class AlternateMachinePerfTest
{
  private static final long RUNNING_TIME = 30000;

  public static void main(String[] args) throws Exception
  {
    long lStopTime = System.currentTimeMillis() + RUNNING_TIME;

    StateMachine lMachine = new MajoritiesStateMachine();
    lMachine.initialize(null);

    long lIterations = 0;
    while (System.currentTimeMillis() < lStopTime)
    {
      for (MachineState lState = lMachine.getInitialState();
           !lMachine.isTerminal(lState);
           lState = lMachine.getRandomNextState(lState))
      {
        /* Do nothing */
      }
      lIterations++;
    }

    System.out.println("Performed " + lIterations + " iterations at a rate of " + (lIterations * 1000 / RUNNING_TIME) + "/s");
  }
}
