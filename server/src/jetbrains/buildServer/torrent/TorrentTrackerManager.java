package jetbrains.buildServer.torrent;

import com.intellij.openapi.diagnostic.Logger;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.common.Cleanable;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import com.turn.ttorrent.tracker.TrackerRequestProcessor;
import jetbrains.buildServer.NetworkUtil;
import jetbrains.buildServer.torrent.web.TrackerController;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class TorrentTrackerManager {

  private final static Logger LOG = Logger.getInstance(TorrentTrackerManager.class.getName());

  private final TrackerRequestProcessor myTrackerService;
  private final ConcurrentMap<String, TrackedTorrent> myTorrents;
  private Tracker myTracker;
  private boolean myTrackerRunning;
  private final TorrentConfigurator myConfigurator;
  private Cleanable myTrackerCleanable;

  public TorrentTrackerManager(@NotNull final TorrentConfigurator configurator,
                               @NotNull final EventDispatcher<BuildServerListener> dispatcher) {
    myConfigurator = configurator;
    myTrackerService = new TrackerRequestProcessor();
    myTrackerService.setAcceptForeignTorrents(true);
    myTorrents = new ConcurrentHashMap<String, TrackedTorrent>();
    dispatcher.addListener(new BuildServerAdapter(){
      @Override
      public void serverShutdown() {
        stopTracker();
      }

      @Override
      public void serverStartup() {
        if (configurator.isTrackerEnabled()) {
          startTracker();
        }
      }
    });
    myConfigurator.addPropertyChangeListener(new PropertyChangeListener() {
      public void propertyChange(PropertyChangeEvent evt) {
        if (evt.getNewValue() instanceof Integer){
          integerPropertyChanged(evt.getPropertyName(), (Integer) evt.getNewValue());
        } else if (evt.getNewValue() instanceof Boolean){
          booleanPropertyChanged(evt.getPropertyName(), (Boolean) evt.getNewValue());
        }
      }
    });
  }

  public void booleanPropertyChanged(@NotNull final String propertyName, boolean newValue){
    if (TorrentConfigurator.TRACKER_DEDICATED_PORT.equals(propertyName)){
      condRestartTracker();
    } else if (TorrentConfigurator.TRACKER_ENABLED.equals(propertyName)){
      if (newValue){
        startTracker();
      } else {
        stopTracker();
      }
    }
  }

  public void integerPropertyChanged(@NotNull final String propertyName, int newValue){
    if (TorrentConfigurator.ANNOUNCE_INTERVAL.equals(propertyName)){
      setAnnounceInterval(newValue);
    }
  }

  /**
   * restarts tracker if it's running and does nothing otherwise
   *
   */
  private void condRestartTracker(){
    boolean wasRunning = isTrackerRunning();
    if (wasRunning){
      stopTracker();
      startTracker();
    }
  }

  public void startTracker(){
    myTorrents.clear();

    // if we don't use individual port, we need nothing. Tracker's controller is already initialized.
    if (myConfigurator.isTrackerDedicatedPort()){
      startIndividualPort(myConfigurator.getResolvedOwnAddress());
    }

    //setting peer collection interval to the same as announce interval
    myTrackerCleanable = new Cleanable() {
      public void cleanUp() {
        for (TrackedTorrent torrent : myTorrents.values()) {
          torrent.collectUnfreshPeers(myConfigurator.getTrackerTorrentExpireTimeoutSec());
          if (torrent.getPeers().size() == 0) {
            myTorrents.remove(torrent.getHexInfoHash());
          }
        }
      }
    };
    Client.cleanupProcessor().registerCleanable(myTrackerCleanable);

    myTrackerRunning = true;

    myConfigurator.setAnnounceUrl(getAnnounceUri().toString());
  }

  private void startIndividualPort(@NotNull String trackerAddress) {
    int freePort = NetworkUtil.getFreePort(6969);

    try {
      String announceAddress = String.format("http://%s:%d/announce", trackerAddress, freePort);
      myTracker = new Tracker(freePort, announceAddress, myTrackerService, myTorrents);
      myTracker.setAcceptForeignTorrents(true);
      myTracker.start(false);
      LOG.info("Torrent tracker started on url: " + myTracker.getAnnounceUrl());
    } catch (Exception e) {
      LOG.error("Failed to start torrent tracker, server URL is invalid: ", e);
      throw new RuntimeException(e);
    }
  }

  public void stopTracker() {
    Client.cleanupProcessor().unregisterCleanable(myTrackerCleanable);
    myTrackerRunning = false;
    if (myTracker != null) {
      LOG.info("Stopping torrent tracker");
      myTracker.stop();
    }
  }

  public boolean isTrackerRunning(){
    return myTrackerRunning;
  }

  public boolean isTrackerUsesDedicatedPort() {
    return myConfigurator.isTrackerDedicatedPort();
  }

  public ConcurrentMap<String, TrackedTorrent> getTorrents() {
    return myTorrents;
  }

  public TrackerRequestProcessor getTrackerService() {
    return myTrackerService;
  }

  public void setAnnounceInterval(final int announceInterval){
    myTrackerService.setAnnounceInterval(announceInterval);
  }

  public int getConnectedClientsNum() {
    if (!myTrackerRunning){
      return 0;
    }
    Set<String> uniquePeers = new HashSet<String>();
    for (TrackedTorrent tt : myTorrents.values()) {
      uniquePeers.addAll(tt.getPeers().keySet());
    }
    return uniquePeers.size();
  }

  public int getAnnouncedTorrentsNum() {
    if (!myTrackerRunning){
      return 0;
    }
    return myTorrents.size();
  }

  public URI getAnnounceUri() {
    if (myConfigurator.isTrackerDedicatedPort()){
      return myTracker.getAnnounceURI();
    } else {
      String serverUrl = myConfigurator.getServerAddress();
      if (serverUrl.endsWith("/")){
        serverUrl = serverUrl.substring(0, serverUrl.length()-1);
      }
      return URI.create(serverUrl + TrackerController.PATH);
    }
  }
}