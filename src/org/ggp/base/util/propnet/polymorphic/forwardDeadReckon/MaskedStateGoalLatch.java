package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * A state/mask pair.
 */
public class MaskedStateGoalLatch
{
  private final ForwardDeadReckonInternalMachineState mState;
  private final ForwardDeadReckonInternalMachineState mMask;
  private final int mGoalValue;

  /**
   * Create an initially empty state/mask pair.
   *
   * @param xiStateMachine - a state machine, used to create the masked state.
   * @param xiGoalValue - the goal value for the specified state.
   */
  public MaskedStateGoalLatch(ForwardDeadReckonPropnetStateMachine xiStateMachine, int xiGoalValue)
  {
    mState = xiStateMachine.createEmptyInternalState();
    mMask = xiStateMachine.createEmptyInternalState();
    mGoalValue = xiGoalValue;
  }

  /**
   * Add a proposition to the state mask.
   *
   * @param xiProposition - the proposition to add.
   * @param xiValue       - the value of the proposition.
   */
  public void add(ForwardDeadReckonProposition xiProposition, boolean xiValue)
  {
    mMask.add(xiProposition.getInfo());
    if (xiValue)
    {
      mState.add(xiProposition.getInfo());
    }
  }

  /**
   * @param xiState - the state to test.
   * @return Test whether the specified state matches the masked state.
   */
  public boolean matches(ForwardDeadReckonInternalMachineState xiState)
  {
    // Calculate the XOR of the two states.  This is all the elements that differ.
    xiState.contents.xor(mState.contents);

    // If any of the elements that differ are in the mask, the states don't match.  Otherwise they do.
    boolean lMatches = !xiState.contents.intersects(mMask.contents);

    // Put the state back like it was by XORing the mask state back out of it.
    xiState.contents.xor(mState.contents);

    return lMatches;
  }

  public int getGoalValue()
  {
    return mGoalValue;
  }
}