.class public Lcom/test/Scanner;
.super Ljava/lang/Object;

.method public static scan(Landroid/content/Context;)V
    .registers 4
    const/4 v0, 0x0
    invoke-virtual {v0}, Landroid/net/wifi/WifiManager;->getScanResults()Ljava/util/List;
    invoke-virtual {v0}, Landroid/net/ConnectivityManager;->getActiveNetworkInfo()Landroid/net/NetworkInfo;
    const-string v1, "android.bluetooth.adapter.action.DISCOVERY_FINISHED"
    new-instance v2, Landroid/content/IntentFilter;
    invoke-direct {v2, v1}, Landroid/content/IntentFilter;-><init>(Ljava/lang/String;)V
    return-void
.end method

.method public static otherOverload()V
    .registers 2
    const/4 v0, 0x0
    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Landroid/net/ConnectivityManager;->getActiveNetworkInfo(I)Landroid/net/NetworkInfo;
    return-void
.end method
