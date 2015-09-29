package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;

public class TristateNot extends TristateComponent implements PolymorphicNot
{
  @Override
  public void changeInput(Tristate xiNewValue)
  {
    if (mValue == Tristate.UNKNOWN)
    {
      if (xiNewValue == Tristate.FALSE)
      {
        mValue = Tristate.TRUE;
      }
      else
      {
        mValue = Tristate.FALSE;
      }
      changeOutput();
    }
  }
}
