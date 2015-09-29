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
    assert(!mTesting) : "Can't set value of a proposition that has already been set";
    assert(mValue == Tristate.UNKNOWN) : "Can't set value of proposition whose state is already known - use 'reset'";

    mTesting = true;
    mValue = (xiValue ? Tristate.TRUE : Tristate.FALSE);
    changeOutput();
  }

  @Override
  public void changeInput(Tristate xiNewValue)
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if (mTesting)
    {
      // This is the proposition that we were originally testing.  It now has a definite value and is therefore a latch.
      throw new LatchFoundException(xiNewValue == Tristate.TRUE);
    }

    mValue = xiNewValue;
    changeOutput();

    // If this is a LEGAL prop and it has been latched to FALSE, set the corresponding DOES prop to FALSE.
    if (mValue == Tristate.FALSE)
    {
      TristateProposition lDoes = (TristateProposition)mParent.getLegalInputMap().get(this);
      if (lDoes != null)
      {
        lDoes.changeInput(Tristate.FALSE);
      }
    }
  }

  public class LatchFoundException extends RuntimeException
  {
    public final boolean mPositive;

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
