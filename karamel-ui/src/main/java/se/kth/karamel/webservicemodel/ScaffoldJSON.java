package se.kth.karamel.webservicemodel;

/**
 * JSON representing the new cookbook to be created.
 *
 */
public class ScaffoldJSON {

  private String name;

  public ScaffoldJSON() {
  }

  public ScaffoldJSON(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
