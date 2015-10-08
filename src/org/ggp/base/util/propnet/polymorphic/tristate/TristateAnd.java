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
        changeOutput(xiTurn, false);
      }
      else if (--(mState[xiTurn].mNumUnknownInputs) == 0)
      {
        mState[xiTurn].mValue = Tristate.TRUE;
        changeOutput(xiTurn, false);
      }
    }
  }
}
