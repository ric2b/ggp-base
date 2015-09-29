package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;

public class TristateOr extends TristateComponent implements PolymorphicOr
{
  public TristateOr(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue)
  {
    if (mValue == Tristate.UNKNOWN)
    {
      if (xiNewValue == Tristate.TRUE)
      {
        mValue = Tristate.TRUE;
        changeOutput();
      }
      else if (--mNumUnknownInputs == 0)
      {
        mValue = Tristate.FALSE;
        changeOutput();
      }
    }
  }
}
