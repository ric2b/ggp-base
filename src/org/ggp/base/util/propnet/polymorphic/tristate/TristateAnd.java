package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;

public class TristateAnd extends TristateComponent implements PolymorphicAnd
{
  public TristateAnd(TristatePropNet xiNetwork)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue)
  {
    if (mValue == Tristate.UNKNOWN)
    {
      if (xiNewValue == Tristate.FALSE)
      {
        mValue = Tristate.FALSE;
        changeOutput();
      }
      else if (--mNumUnknownInputs == 0)
      {
        mValue = Tristate.TRUE;
        changeOutput();
      }
    }
  }
}
