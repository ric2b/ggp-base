package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class TristateTransition extends TristateComponent implements PolymorphicTransition
{
  public TristateTransition(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

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
