package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * A state/mask pair.
 */
public class ForwardDeadReckonMaskedState
{
  private final ForwardDeadReckonInternalMachineState mState;
  private final ForwardDeadReckonInternalMachineState mMask;

  /**
   * Create an initially empty state/mask pair.
   *
   * @param xiStateMachine - a state machine, used to create the masked state.
   */
  public ForwardDeadReckonMaskedState(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    mState = xiStateMachine.createEmptyInternalState();
    mMask = xiStateMachine.createEmptyInternalState();
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
    mState.contents.xor(xiState.contents);

    // If any of the elements that differ are in the mask, the states don't match.  Otherwise they do.
    boolean lMatches = !mState.contents.intersects(mMask.contents);

    // Put the state back like it was by XORing the supplied state back out of it.
    mState.contents.xor(xiState.contents);

    return lMatches;
  }
}