package com.brentvatne.exoplayer;

public class InstreamAdInfo {
  private String adTagUrl = null;
  private String adLang = null;

  public InstreamAdInfo(String adTagUrl, String adLang) {
    this.adTagUrl = adTagUrl;
    this.adLang = adLang;
  }

  public String getAdTagUrl() {
    return this.adTagUrl;
  }

  public String getAdLang() {
    return this.adLang;
  }
}