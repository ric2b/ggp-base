package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;

public class TristateOr extends TristateComponent implements PolymorphicOr
{
  public TristateOr(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue, int xiTurn)
  {
    if (mState[xiTurn].mValue == Tristate.UNKNOWN)
    {
      if (xiNewValue == Tristate.TRUE)
      {
        mState[xiTurn].mValue = Tristate.TRUE;
        propagateOutput(xiTurn, false);
      }
      else if (--(mState[xiTurn].mNumUnknownInputs) == 0)
      {
        mState[xiTurn].mValue = Tristate.FALSE;
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

      // If FALSE, all the upstream components must be FALSE.  (This is an OR gate.)
      if (xiNewValue == Tristate.FALSE)
      {
        for (TristateComponent lInput : getInputs())
        {
          assert(lInput.mState[xiTurn].mValue != Tristate.TRUE);
          lInput.changeOutput(Tristate.FALSE, xiTurn);
        }
      }
    }
  }
}
