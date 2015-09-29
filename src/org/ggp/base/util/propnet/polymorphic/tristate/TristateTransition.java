package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class TristateTransition extends TristateComponent implements PolymorphicTransition
{
  @Override
  public void changeInput(Tristate xiNewValue)
  {
    if (mValue != Tristate.UNKNOWN)
    {
      mValue = xiNewValue;
      changeOutput();
    }
  }
}
