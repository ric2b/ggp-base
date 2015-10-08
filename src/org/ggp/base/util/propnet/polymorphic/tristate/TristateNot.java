package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;

public class TristateNot extends TristateComponent implements PolymorphicNot
{
  public TristateNot(TristatePropNet xiNetwork)
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
        mState[xiTurn].mValue = Tristate.TRUE;
      }
      else
      {
        mState[xiTurn].mValue = Tristate.FALSE;
      }
      changeOutput(xiTurn, false);
    }
  }
}
