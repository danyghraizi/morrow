# JNI bridge: names are looked up by the VM, never rename them.
-keep class dev.morrow.llm.Llama { *; }
-keepclasseswithmembers class dev.morrow.llm.Llama$TokenSink { *; }
-keep class dev.morrow.notify.ReminderReceiver { *; }
-keep class dev.morrow.notify.BootReceiver { *; }
