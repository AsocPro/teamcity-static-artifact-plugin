package jetbrains.buildServer.torrent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* @author Sergey.Pak
*         Date: 10/4/13
*         Time: 11:33 AM
*/
public class ParsedArtifactPath {
  private static final Pattern FILE_PATH_PATTERN = Pattern.compile("(.*?)/repository/download/([^/]+)/([^/]+)/(.+?)(\\?branch=.+)?");

  @NotNull
  private final String myServerUrl;
  @NotNull
  private final String myModule;
  @NotNull
  private final String myProject;
  @NotNull
  private final String myBuild;
  @NotNull
  private final String myRevision;
  @NotNull
  private final String myBuildNumber;
  @NotNull
  private final String myArtifactPath;
  @Nullable
  private final String myBranch;

  ParsedArtifactPath(@NotNull final String artifactUrl) throws IllegalArgumentException{
    final Matcher matcher = FILE_PATH_PATTERN.matcher(artifactUrl);
    if (!matcher.matches()){
      throw new IllegalArgumentException("Unable to parse " + artifactUrl);
    }
    myServerUrl = matcher.group(1);
    myModule = matcher.group(2);
    myRevision = matcher.group(3);
    myArtifactPath = matcher.group(4);
    myBranch = matcher.group(5);
    String[] moduleArray = myModule.split("_");
    if(moduleArray.length == 2) {
      myProject = moduleArray[0];
      myBuild = moduleArray[1];
    }
    else {//TODO: handle trying each different _ to split on in case there is an _ in the Project/Build Name
      myProject = "MYPROJECT";
      myBuild = "MYBUILD";
    }
    myBuildNumber = myRevision.substring(0, myRevision.indexOf("."));
  }

  @NotNull
  public String getServerUrl() {
    return myServerUrl;
  }

  @NotNull
  public String getArtifactPath() {
    return myArtifactPath;
  }

  @Nullable
  public String getBranch() {
    return myBranch;
  }

  public String getTorrentUrl(){
    return String.format("http://localhost:88/%s/%s/%s/%s%s",
            myProject, myBuild, myBuildNumber, myArtifactPath,
            myBranch == null ? "" : "?branch="+ myBranch);
  }

  public String getTorrentPath(){
    return TorrentTransportFactory.TEAMCITY_TORRENTS + myArtifactPath + ".torrent";
  }

  public String getRelativeLinkPath(){
    return String.format("%s/%s/64/testfile1", myProject, myBuild, myRevision, myArtifactPath);
  }
}
