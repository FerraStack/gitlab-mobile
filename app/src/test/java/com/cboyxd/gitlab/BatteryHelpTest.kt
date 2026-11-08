package com.cboyxd.gitlab

import com.cboyxd.gitlab.notifications.BatteryHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHelpTest {

  @Test
  fun `makers map to their dontkillmyapp guide`() {
    assertEquals("https://dontkillmyapp.com/xiaomi", BatteryHelp.forManufacturer("Xiaomi").guideUrl)
    assertEquals("https://dontkillmyapp.com/xiaomi", BatteryHelp.forManufacturer("POCO").guideUrl)
    assertEquals("https://dontkillmyapp.com/xiaomi", BatteryHelp.forManufacturer(" redmi ").guideUrl)
    assertEquals("https://dontkillmyapp.com/samsung", BatteryHelp.forManufacturer("samsung").guideUrl)
    assertEquals("https://dontkillmyapp.com/oneplus", BatteryHelp.forManufacturer("OnePlus").guideUrl)
    assertEquals("https://dontkillmyapp.com/google", BatteryHelp.forManufacturer("Google").guideUrl)
    assertTrue(BatteryHelp.forManufacturer("Samsung").steps.contains("Never sleeping apps"))
    assertTrue(BatteryHelp.forManufacturer("Xiaomi").steps.contains("Autostart"))
  }

  @Test
  fun `unknown makers get general advice and the guide index`() {
    val help = BatteryHelp.forManufacturer("fairphone")
    assertEquals("Fairphone", help.maker)
    assertEquals("https://dontkillmyapp.com/", help.guideUrl)
    assertEquals("This phone", BatteryHelp.forManufacturer("").maker)
  }

  @Test
  fun `the card shows once instant notifications are on and the app is still optimized`() {
    assertTrue(BatteryHelp.showCard(instantEnabled = true, ignoringOptimizations = false, dismissed = false))
    assertFalse(BatteryHelp.showCard(instantEnabled = false, ignoringOptimizations = false, dismissed = false))
    assertFalse(BatteryHelp.showCard(instantEnabled = true, ignoringOptimizations = true, dismissed = false))
    assertFalse(BatteryHelp.showCard(instantEnabled = true, ignoringOptimizations = false, dismissed = true))
  }
}
