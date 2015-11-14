package org.ggp.base.util.propnet.polymorphic.tristate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;

public class TristateProposition extends TristateComponent implements PolymorphicProposition
{
  private static final Logger LOGGER = LogManager.getLogger();

  private GdlSentence mName;
  private boolean mTesting;

  public TristateProposition(TristatePropNet xiNetwork, GdlSentence xiName)
  {
    super(xiNetwork);
    mName = xiName;
  }

  @Override
  public GdlSentence getName()
  {
    return mName;
  }

  @Override
  public void setName(GdlSentence xiNewName)
  {
    mName = xiNewName;
  }

  @Override
  public void reset()
  {
    super.reset();
    mTesting = false;
  }

  @Override
  public void setValue(boolean xiValue)
  {
    throw new RuntimeException("Use isLatch instead");
  }

  /**
   * Test whether this proposition is a latch proposition.
   *
   * @param xiValue - the value to test.
   * @return whether the proposition is a latch (for the specified value).
   */
  public boolean isLatch(boolean xiValue)
  {
    LOGGER.info("Checking whether " + getName() + " is a " + (xiValue ? "+" : "-") + "ve latch");

    assert(!mTesting) : "Can't set value of a proposition that has already been set - use 'reset' on the parent network";

    // If the value has already been set, it must have been set as part of assuming that init is false.  (Often this
    // will manifest in the first counter proposition appearing to be a negative latch.)  In that case, there's no need
    // to propagate.  Instead, we can just read the next-turn value.
    if (mState[1].mValue != Tristate.UNKNOWN)
    {
      return ((( xiValue) && mState[1].mValue == Tristate.TRUE  && mState[2].mValue == Tristate.TRUE) ||
              ((!xiValue) && mState[1].mValue == Tristate.FALSE && mState[2].mValue == Tristate.FALSE));
    }

    mTesting = true;
    mState[0].mValue = (xiValue ? Tristate.FALSE : Tristate.TRUE);
    mState[1].mValue = (xiValue ? Tristate.TRUE : Tristate.FALSE);

    boolean lLatch = false;
    try
    {
      // Do forward propagation.
      propagateOutput(0, false);
      propagateOutput(1, false);

      // Do backward propagation.
      getSingleInput().changeOutput(mState[0].mValue, 0);
      getSingleInput().changeOutput(mState[1].mValue, 1);
    }
    catch (LatchFoundException lEx)
    {
      // Check that the sense of the latch is as exception.  If a true value in this turn forces a false value in the
      // next and false forces true, then this could be a turn marker, but we aren't currently interested.
      lLatch = (xiValue == lEx.mPositive);
    }

    return lLatch;
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn)
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if ((mTesting) && (xiTurn == 2))
    {
      // This is the proposition that we were originally testing.  It now has a definite value and is therefore a latch.
      throw new LatchFoundException(xiNewValue == Tristate.TRUE);
    }

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      LOGGER.info(mName + "has become " + xiNewValue + " in turn " + xiTurn + " by forward prop");

      mState[xiTurn].mValue = xiNewValue;
      propagateOutput(xiTurn, false);

      // If this is a LEGAL prop and it has become FALSE, set the corresponding DOES prop to FALSE in the next turn.
      if (mState[xiTurn].mValue == Tristate.FALSE)
      {
        TristateProposition lDoes = (TristateProposition)mParent.getLegalInputMap().get(this);
        if ((lDoes != null) && (xiTurn < 2))
        {
          lDoes.changeInput(Tristate.FALSE, xiTurn + 1);
        }
      }
    }
  }

  @Override
  public void changeOutput(Tristate xiNewValue, int xiTurn)
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      LOGGER.info(mName + "has become " + xiNewValue + " in turn " + xiTurn + " by backward prop");

      // We've learned our output value from downstream.
      mState[xiTurn].mValue = xiNewValue;

      // Tell any other downstream components.
      propagateOutput(xiTurn, false);

      // Tell the (single) upstream component (if any - DOES propositions don't have inputs).
      if (getInputs().size() == 1)
      {
        getSingleInput().changeOutput(xiNewValue, xiTurn);
      }
    }
  }

  /**
   * Exception thrown to indicate that a latch has been found.
   */
  public class LatchFoundException extends RuntimeException
  {
    public final boolean mPositive;

    /**
     * Create a latch-found exception.
     *
     * @param xiPositive - true if this is a positive latch, false for a negative latch.
     */
    LatchFoundException(boolean xiPositive)
    {
      mPositive = xiPositive;
    }

    @Override
    public String toString()
    {
      return getName() + " is a " + (mPositive ? "positive" : "negative") + " latch";
    }
  }
}
