package org.teraschool.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
  @Test
  void boundaries() {
    var classes = new ClassFileImporter().importPackages("org.teraschool");
    for (String module :
        new String[] {
          "platform", "identity", "organization", "archive", "product", "enrollment", "funds"
        })
      noClasses()
          .that()
          .resideOutsideOfPackage("org.teraschool." + module + "..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.teraschool." + module + ".internal..")
          .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage(
            "org.teraschool.platform..",
            "org.teraschool.identity..",
            "org.teraschool.organization..",
            "org.teraschool.archive..",
            "org.teraschool.product..",
            "org.teraschool.enrollment..",
            "org.teraschool.funds..",
            "org.teraschool.teaching..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.teraschool.server..")
        .check(classes);
    slices().matching("org.teraschool.(*)..").should().beFreeOfCycles().check(classes);
  }
}
