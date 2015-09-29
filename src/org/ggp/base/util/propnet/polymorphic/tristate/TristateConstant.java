package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;

public class TristateConstant extends TristateComponent implements PolymorphicConstant
{
  public TristateConstant(TristatePropNet xiNetwork, boolean xiValue)
  {
    super(xiNetwork);
  }

  @Override
  public void changeInput(Tristate xiNewValue)
  {
    throw new RuntimeException("Constants don't have inputs!");
  }
}
