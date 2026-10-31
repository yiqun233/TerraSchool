package org.teraschool.identity.api;

public interface CurrentActor {
  Actor requireOrganizationReader();

  Actor requireOrganizationMaintainer();
}
