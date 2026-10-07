.class public Lcom/test/NetService;
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

.method public onStartCommand(Landroid/content/Intent;II)I
    .registers 5
    invoke-static {p0}, Lcom/test/Scanner;->scan(Landroid/content/Context;)V
    const/4 v0, 0x1
    return v0
.end method
