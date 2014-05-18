package org.ggp.base.player;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.event.PlayerDroppedPacketEvent;
import org.ggp.base.player.event.PlayerReceivedMessageEvent;
import org.ggp.base.player.event.PlayerSentMessageEvent;
import org.ggp.base.player.gamer.Gamer;
import org.ggp.base.player.request.factory.RequestFactory;
import org.ggp.base.player.request.grammar.AbortRequest;
import org.ggp.base.player.request.grammar.Request;
import org.ggp.base.player.request.grammar.StopRequest;
import org.ggp.base.server.event.ServerAbortedMatchEvent;
import org.ggp.base.server.event.ServerCompletedMatchEvent;
import org.ggp.base.util.http.HttpReader;
import org.ggp.base.util.http.HttpWriter;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.observer.Event;
import org.ggp.base.util.observer.Observer;
import org.ggp.base.util.observer.Subject;

public final class GamePlayer extends Thread implements Subject
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final int            port;
  private final Gamer          gamer;
  private ServerSocket         listener;
  private final List<Observer> observers;

  public GamePlayer(int port, Gamer gamer) throws IOException
  {
    observers = new ArrayList<Observer>();
    listener = null;

    while (listener == null)
    {
      try
      {
        listener = new ServerSocket(port);
      }
      catch (IOException ex)
      {
        listener = null;
        port++;
        LOGGER.warn("Failed to start gamer on port: " + (port - 1) + " trying port " + port);
      }
    }

    this.port = port;
    this.gamer = gamer;

    gamer.notePort(port);

    setName("GamePlayer - " + this.gamer.getName() + " (" + this.port + ")");
  }

  @Override
  public void addObserver(Observer observer)
  {
    observers.add(observer);
  }

  @Override
  public void notifyObservers(Event event)
  {
    for (Observer observer : observers)
    {
      observer.observe(event);
    }
  }

  public final int getGamerPort()
  {
    return port;
  }

  public final Gamer getGamer()
  {
    return gamer;
  }

  @Override
  public void run()
  {
    while (!isInterrupted())
    {
      try
      {
        Socket connection = listener.accept();
        String in = HttpReader.readAsServer(connection);
        if (in.length() == 0)
        {
          throw new IOException("Empty message received.");
        }

        notifyObservers(new PlayerReceivedMessageEvent(in));
        GamerLogger.log("GamePlayer",
                        "[Received at " + System.currentTimeMillis() + "] " +
                            in,
                        GamerLogger.LOG_LEVEL_DATA_DUMP);

        Request request = new RequestFactory().create(gamer, in);
        String out = request.process(System.currentTimeMillis());

        HttpWriter.writeAsServer(connection, out);
        connection.close();
        notifyObservers(new PlayerSentMessageEvent(out));

        if (request instanceof AbortRequest)
        {
          notifyObservers(new ServerAbortedMatchEvent());
        }
        else if (request instanceof StopRequest)
        {
          notifyObservers(new ServerCompletedMatchEvent(null));
        }

        GamerLogger.log("GamePlayer",
                        "[Sent at " + System.currentTimeMillis() + "] " + out,
                        GamerLogger.LOG_LEVEL_DATA_DUMP);
      }
      catch (Exception e)
      {
        notifyObservers(new PlayerDroppedPacketEvent());
      }
    }
  }
}