.class public Lcom/test/SpyService;
.super Landroid/app/Service;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Landroid/app/Service;-><init>()V
    return-void
.end method

.method public onBind(Landroid/content/Intent;)Landroid/os/IBinder;
    .registers 3
    const/4 v0, 0x0
    return-object v0
.end method

.method public onCreate()V
    .registers 4
    new-instance v0, Ljava/lang/Thread;
    new-instance v1, Lcom/test/Worker;
    invoke-direct {v1}, Lcom/test/Worker;-><init>()V
    invoke-direct {v0, v1}, Ljava/lang/Thread;-><init>(Ljava/lang/Runnable;)V
    invoke-virtual {v0}, Ljava/lang/Thread;->start()V
    new-instance v1, Lcom/test/DynReceiver;
    invoke-direct {v1}, Lcom/test/DynReceiver;-><init>()V
    const/4 v2, 0x0
    invoke-virtual {p0, v1, v2}, Lcom/test/SpyService;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
    return-void
.end method

.method public onStartCommand(Landroid/content/Intent;II)I
    .registers 5
    invoke-static {p0}, Lcom/test/Helper;->collect(Landroid/content/Context;)V
    const/4 v0, 0x1
    return v0
.end method
