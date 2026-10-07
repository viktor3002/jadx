.class public Lcom/test/Clicker;
.super Landroid/accessibilityservice/AccessibilityService;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Landroid/accessibilityservice/AccessibilityService;-><init>()V
    return-void
.end method

.method public onAccessibilityEvent(Landroid/view/accessibility/AccessibilityEvent;)V
    .registers 5
    const/4 v0, 0x0
    const/4 v1, 0x0
    const/4 v2, 0x0
    invoke-virtual {p0, v0, v1, v2}, Lcom/test/Clicker;->dispatchGesture(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z
    return-void
.end method

.method public onInterrupt()V
    .registers 1
    return-void
.end method
