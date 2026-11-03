package com.hershaleh.videostop

import android.app.admin.DeviceAdminReceiver

/**
 * מנהל מכשיר ללא הרשאות מיוחדות. כל עוד הוא פעיל, המערכת לא מאפשרת להסיר את האפליקציה;
 * כדי להסיר יש קודם לבטל את ההפעלה (ומסך הביטול מוגן בקוד).
 */
class AdminReceiver : DeviceAdminReceiver()
