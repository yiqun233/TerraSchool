package org.teraschool.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
  @Test
  void boundaries() {
    var classes = new ClassFileImporter().importPackages("org.teraschool");
    var modules =
        classes.stream()
            .map(type -> type.getPackageName().split("\\."))
            .filter(parts -> parts.length > 2 && !parts[2].equals("server"))
            .map(parts -> parts[2])
            .collect(java.util.stream.Collectors.toSet());
    for (String module : modules)
      noClasses()
          .that()
          .resideOutsideOfPackage("org.teraschool." + module + "..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.teraschool." + module + ".internal..")
          .check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackage("org.teraschool.server..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.teraschool.server..")
        .check(classes);
    slices().matching("org.teraschool.(*)..").should().beFreeOfCycles().check(classes);
  }
}
