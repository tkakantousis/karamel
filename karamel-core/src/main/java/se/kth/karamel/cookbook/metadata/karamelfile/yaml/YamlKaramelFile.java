/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.kth.karamel.cookbook.metadata.karamelfile.yaml;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author kamal
 */
public class YamlKaramelFile {

  private List<YamlDependency> dependencies = new ArrayList<>();

  public List<YamlDependency> getDependencies() {
    return dependencies;
  }

  public void setDependencies(List<YamlDependency> dependencies) {
    if (dependencies != null) {
      this.dependencies = dependencies;
    }
  }

}
