package com.nononsenseapps.feeder.ui.compose.sync

import androidx.annotation.StringRes
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.UserSettings

/**
 * Level-2 grouping of settings in the sync-selection tree.
 *
 * Forward-compatible by design: [syncCategory] has an `else` branch, so any future [UserSettings]
 * entry that isn't mapped here still shows up (under [OTHER]) instead of breaking the screen.
 */
enum class SyncSettingCategory(
    @StringRes val titleRes: Int,
) {
    APPEARANCE(R.string.sync_cat_appearance),
    READING(R.string.sync_cat_reading),
    SYNC(R.string.sync_cat_sync),
    AI(R.string.sync_cat_ai),
    OTHER(R.string.sync_cat_other),
}

fun UserSettings.syncCategory(): SyncSettingCategory =
    when (this) {
        UserSettings.SETTING_THEME,
        UserSettings.SETTING_DARK_THEME,
        UserSettings.SETTING_DYNAMIC_THEME,
        UserSettings.SETTING_SHOW_FAB,
        UserSettings.SETTING_FEED_ITEM_STYLE,
        UserSettings.SETTING_FONT,
        UserSettings.SETTING_TEXT_SCALE,
        UserSettings.SETTING_OPEN_DRAWER_ON_FAB,
        UserSettings.SETTING_SHOW_TITLE_UNREAD_COUNT,
        -> SyncSettingCategory.APPEARANCE

        UserSettings.SETTING_SORT,
        UserSettings.SETTING_SWIPE_AS_READ,
        UserSettings.SETTING_DEFAULT_OPEN_ITEM_WITH,
        UserSettings.SETTING_OPEN_LINKS_WITH,
        UserSettings.SETTING_OPEN_ADJACENT,
        UserSettings.SETTING_PAGING_MODE,
        UserSettings.SETTING_ANIMATED_PAGING,
        UserSettings.SETTING_IS_MARK_AS_READ_ON_SCROLL,
        UserSettings.SETTING_READALOUD_USE_DETECT_LANGUAGE,
        UserSettings.SETTING_MAX_LINES,
        UserSettings.SETTINGS_FILTER_SAVED,
        UserSettings.SETTINGS_FILTER_RECENTLY_READ,
        UserSettings.SETTINGS_FILTER_READ,
        UserSettings.SETTINGS_LIST_SHOW_ONLY_TITLES,
        UserSettings.SETTING_LIST_SHOW_READING_TIME,
        UserSettings.SETTING_IMG_ONLY_WIFI,
        UserSettings.SETTING_IMG_SHOW_THUMBNAILS,
        -> SyncSettingCategory.READING

        UserSettings.SETTING_ADDED_FEEDER_NEWS,
        UserSettings.SETTING_SYNC_ONLY_CHARGING,
        UserSettings.SETTING_SYNC_ONLY_WIFI,
        UserSettings.SETTING_SYNC_FREQ,
        UserSettings.SETTING_SYNC_ON_RESUME,
        UserSettings.SETTING_MAX_ITEM_COUNT_PER_FEED,
        -> SyncSettingCategory.SYNC

        UserSettings.SETTING_OPENAI_KEY,
        UserSettings.SETTING_OPENAI_MODEL_ID,
        UserSettings.SETTING_OPENAI_URL,
        UserSettings.SETTING_OPENAI_AZURE_VERSION,
        UserSettings.SETTING_OPENAI_AZURE_DEPLOYMENT_ID,
        UserSettings.SETTING_OPENAI_REQUEST_TIMEOUT_SECONDS,
        UserSettings.SETTING_SUMMARY_PROMPT,
        UserSettings.SETTING_SUMMARY_PROMPT_BY_TAG,
        UserSettings.SETTING_BLOCKLIST_APPLY_TO_SUMMARIES,
        UserSettings.SETTING_PREFERRED_TRANSLATION_LANGUAGE,
        UserSettings.SETTING_TRANSLATION_API_KEY,
        UserSettings.SETTING_TRANSLATION_API_MODEL_ID,
        UserSettings.SETTING_TRANSLATION_API_URL,
        UserSettings.SETTING_TRANSLATION_API_AZURE_VERSION,
        UserSettings.SETTING_TRANSLATION_API_AZURE_DEPLOYMENT_ID,
        UserSettings.SETTING_TRANSLATION_API_REQUEST_TIMEOUT_SECONDS,
        UserSettings.SETTING_TRANSLATE_ARTICLE_PREVIEWS_BY_DEFAULT,
        UserSettings.SETTING_TRANSLATE_ARTICLES_BY_DEFAULT,
        -> SyncSettingCategory.AI

        else -> SyncSettingCategory.OTHER
    }

/**
 * Human-readable label for a setting, derived from its preference key so that new settings always
 * get a sensible label without any extra wiring.
 */
fun UserSettings.syncLabel(): String =
    key
        .removePrefix("pref_")
        .removePrefix("prefs_")
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { it.uppercase() }
