package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;

public class TristateAnd extends TristateComponent implements PolymorphicAnd
{
  public TristateAnd(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn)
  {
    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      if (xiNewValue == Tristate.FALSE)
      {
        mState[xiTurn].mValue = Tristate.FALSE;
        propagateOutput(xiTurn, false);
      }
      else if (--(mState[xiTurn].mNumUnknownInputs) == 0)
      {
        mState[xiTurn].mValue = Tristate.TRUE;
        propagateOutput(xiTurn, false);
      }
    }
  }

  @Override
  public void changeOutput(Tristate xiNewValue, int xiTurn)
  {
    assert(xiNewValue != Tristate.UNKNOWN);

    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      // We've learned our output value from downstream.
      mState[xiTurn].mValue = xiNewValue;

      // Tell any other downstream components.
      propagateOutput(xiTurn, false);

      // If TRUE, all the upstream components must be TRUE.  (This is an AND gate.)
      if (xiNewValue == Tristate.TRUE)
      {
        for (TristateComponent lInput : getInputs())
        {
          assert(lInput.mState[xiTurn].mValue != Tristate.FALSE);
          lInput.changeOutput(Tristate.TRUE, xiTurn);
        }
      }
    }
  }
}
