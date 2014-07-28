package org.ggp.base.player.request.grammar;

/**
 * @author steve
 *  Pseudo-request used to encapsulate error returns when the error
 *  occurs before a concrete 'real' Request instance can be constructed
 */
public class ErrorPseudoRequest extends Request
{
  private final String errorReturnString;
  private final String matchId;

  /**
   * @param theMatchId
   * @param returnErrorString
   */
  public ErrorPseudoRequest(String theMatchId, String returnErrorString)
  {
    errorReturnString = returnErrorString;
    this.matchId = theMatchId;
  }

  @Override
  public String process(long xiReceptionTime)
  {
    return errorReturnString;
  }

  @Override
  public String getMatchId()
  {
    return matchId;
  }

  @Override
  public String toString()
  {
    return "error (" + errorReturnString + ")";
  }
}
