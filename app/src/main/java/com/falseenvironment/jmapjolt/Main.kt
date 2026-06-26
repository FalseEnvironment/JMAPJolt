package com.falseenvironment.jmapjolt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.Patterns
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.graphics.PorterDuff
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : AppCompatActivity() {

    internal enum class SwipeAction {
        DELETE,
        ARCHIVE,
        MARK_READ,
        MARK_SPAM
    }
    internal enum class SettingsSection {
        ROOT,
        GENERAL,
        SWIPE,
        UNIFIED_PUSH,
        THEME
    }

    private data class TestNotificationResult(val success: Boolean, val httpCode: Int?)

    internal lateinit var drawerLayout: DrawerLayout
    internal lateinit var navigationView: NavigationView
    internal lateinit var toolbar: Toolbar
    internal lateinit var drawerToggle: ActionBarDrawerToggle
    internal lateinit var onboardingContainer: LinearLayout
    internal lateinit var onboardingBottomBar: android.widget.RelativeLayout
    internal lateinit var onboardingPager: androidx.viewpager2.widget.ViewPager2
    internal lateinit var onboardingNextFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    internal lateinit var onboardingDots: LinearLayout
    internal var loginFromOnboarding = false
    internal var onboardingPermRefresh: (() -> Unit)? = null
    internal var pendingMailboxShow = false
    internal lateinit var loginContainer: LinearLayout
    internal lateinit var loginBackBtn: ImageView
    internal lateinit var loadingOverlay: FrameLayout
    internal lateinit var settingsContainer: ScrollView
    internal lateinit var settingsMenuContainer: LinearLayout
    internal lateinit var settingsGeneralContainer: LinearLayout
    internal lateinit var settingsGeneralHeader: LinearLayout
    internal lateinit var settingsGeneralContent: LinearLayout
    internal lateinit var settingsGeneralChevron: ImageView
    private lateinit var settingsLabelsContainer: LinearLayout
    internal lateinit var settingsLabelsHeader: LinearLayout
    internal lateinit var settingsLabelsContent: LinearLayout
    internal lateinit var settingsLabelsChevron: ImageView
    internal lateinit var settingsSwipeContainer: LinearLayout
    internal lateinit var settingsUnifiedPushContainer: LinearLayout
    internal lateinit var settingsUnifiedPushHeader: LinearLayout
    internal lateinit var settingsUnifiedPushContent: LinearLayout
    internal lateinit var settingsUnifiedPushChevron: ImageView

    internal lateinit var settingsThemeContainer: LinearLayout
    internal lateinit var settingsThemeHeader: LinearLayout
    internal lateinit var settingsThemeContent: LinearLayout
    internal lateinit var settingsThemeChevron: ImageView
    internal lateinit var settingsCalendarChevron: ImageView
    internal lateinit var settingsImportIcsRow: TextView
    internal lateinit var settingsExportIcsRow: TextView
    internal lateinit var settingsInfoRow: LinearLayout
    internal lateinit var settingsInfoIcon: ImageView
    internal lateinit var settingsInfoArrow: ImageView
    internal lateinit var loadImagesSwitch: SwitchCompat
    internal lateinit var loadFaviconsSwitch: SwitchCompat
    // Guards the favicon switch listener against re-entry while we toggle it programmatically.
    internal var suppressFaviconToggle = false
    internal var themeIdx: Int = 0
    internal lateinit var themeDropdown: LinearLayout
    internal lateinit var themeDropdownText: TextView
    internal lateinit var emailInput: EditText
    internal lateinit var passwordInput: EditText
    internal lateinit var serverUrlInput: EditText
    internal lateinit var loginButton: Button
    internal lateinit var mailboxContainer: FrameLayout
    internal lateinit var emailsRecyclerView: RecyclerView
    internal lateinit var emailDetailContainer: EmailDetailContainer
    internal lateinit var detailFrom: TextView
    internal lateinit var detailHeaderRow: LinearLayout
    internal lateinit var detailSubject: TextView
    internal lateinit var detailDate: TextView
    internal lateinit var detailToText: TextView
    internal lateinit var detailMoreButton: ImageView
    internal lateinit var searchChipsScroll: android.widget.HorizontalScrollView
    internal lateinit var searchChipsRow: LinearLayout
    internal var searchScope: Int? = null
    internal lateinit var detailBody: LinearLayout
    internal lateinit var detailScroll: androidx.core.widget.NestedScrollView
    internal var detailBarHidden = false
    internal var detailBarHeight = 0
    internal var detailBarLastToggleMs = 0L
    internal var detailSwipeAnimating = false
    internal val prefetchingIds = mutableSetOf<String>()
    internal lateinit var detailWebView: android.webkit.WebView
    // Preview panel that slides in with the finger during detail swipes,
    // showing the adjacent email's content instead of an empty gap.
    internal var detailPreviewPanel: LinearLayout? = null
    internal var detailPreviewWebView: android.webkit.WebView? = null
    internal var detailPreviewKey: String? = null
    internal lateinit var mailSwipeRefresh: SwipeRefreshLayout
    internal lateinit var unifiedPushSwitch: SwitchCompat
    internal lateinit var sseSwitch: SwitchCompat
    internal lateinit var emptyStateView: TextView
    internal lateinit var status: TextView
    internal lateinit var customTopBar: LinearLayout
    internal lateinit var topBarAccentArea: LinearLayout
    internal lateinit var settingsEditLabelsButton: TextView
    internal lateinit var folderLabel: TextView
    internal lateinit var searchBarMenuIcon: ImageView
    internal lateinit var searchBarTitle: TextView
    internal lateinit var searchBarContainer: LinearLayout
    internal var swipeRightActionIdx: Int = 0
    internal var swipeLeftActionIdx: Int = 0
    internal lateinit var swipeRightDropdown: LinearLayout
    internal lateinit var swipeLeftDropdown: LinearLayout
    internal lateinit var swipeRightDropdownText: TextView
    internal lateinit var swipeLeftDropdownText: TextView
    internal lateinit var settingsCalProviderDropdown: LinearLayout
    internal lateinit var settingsCalProviderText: TextView
    internal lateinit var calendarEnabledSwitch: SwitchCompat
    internal lateinit var settingsCalAddProviderButton: TextView
    internal lateinit var topBarSendButton: ImageView
    internal lateinit var detailReplyButton: ImageView
    internal lateinit var detailForwardButton: ImageView
    internal lateinit var detailArchiveButton: ImageView
    internal lateinit var detailTrashButton: ImageView
    internal lateinit var detailMoveButton: ImageView
    internal lateinit var detailStarButton: ImageView
    internal var currentDetailEmail: DisplayEmail? = null
    internal lateinit var quoteIndicatorRow: LinearLayout
    internal lateinit var quoteIndicatorLabel: TextView
    internal lateinit var quoteIndicatorRemove: ImageView
    internal lateinit var quoteIndicatorDivider: View
    // Faithful HTML of the original message, appended verbatim at send time (reply/forward).
    internal var pendingQuoteHtml: String? = null
    internal lateinit var fabCompose: com.google.android.material.floatingactionbutton.FloatingActionButton
    internal lateinit var composeContainer: LinearLayout
    internal lateinit var composeSendButton: ImageView
    internal lateinit var composeFromLabel: LinearLayout
    internal lateinit var composeFromText: TextView
    internal lateinit var composeToChipsGroup: com.google.android.material.chip.ChipGroup
    internal lateinit var composeCcChipsGroup: com.google.android.material.chip.ChipGroup
    internal lateinit var composeBccChipsGroup: com.google.android.material.chip.ChipGroup
    internal lateinit var composeCategoryTabs: LinearLayout
    internal val recipientEmails = mutableListOf<String>()
    internal val ccEmails = mutableListOf<String>()
    internal val bccEmails = mutableListOf<String>()
    // Active recipient category: 0 = To, 1 = Cc, 2 = Bcc.
    internal var composeCategory = 0
    internal var selectedFromEmail = ""
    internal lateinit var composeToInput: EditText
    internal lateinit var composeSubjectInput: EditText
    internal lateinit var composeBodyInput: EditText
    internal lateinit var formatToolbar: LinearLayout
    internal lateinit var formatToolbarRow: LinearLayout
    internal lateinit var composeAttachButton: ImageView
    internal lateinit var attachmentChipScroll: HorizontalScrollView
    internal lateinit var attachmentChipContainer: LinearLayout
    internal lateinit var attachmentChipDivider: View
    internal val activeFormats = mutableSetOf<String>()
    internal val formatButtons = mutableMapOf<String, View>()
    // List mode for the compose editor: 0 = none, 1 = bullet, 2 = numbered.
    internal var composeListMode = 0
    internal var composeListNextNumber = 1
    // Guards the body TextWatcher against re-entrancy during programmatic list edits.
    internal var composeSelfEdit = false

    data class AttachmentData(val uri: Uri, val name: String, val mimeType: String, val size: Long)
    internal val pendingAttachments = mutableListOf<AttachmentData>()

    internal val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { addAttachment(it) } }

    internal val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { addAttachment(it) } }

    internal val requestStoragePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions resolved; user can try picking again */ }

    /** Import a .ics file picked via the Storage Access Framework. */
    internal val importIcsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { doImportIcs(it) } }

    /** Export all local events to a .ics file created via the Storage Access Framework. */
    internal val exportIcsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri -> uri?.let { doExportIcs(it) } }
    internal lateinit var drawerAccountName: TextView
    private lateinit var drawerAccountEmail: TextView
    private lateinit var drawerAccountAvatar: ImageView
    private lateinit var drawerAccountRow: LinearLayout
    private lateinit var drawerAccountArrow: ImageView
    private lateinit var drawerAccountsList: LinearLayout

    /** Email whose avatar is being changed by the picker; refresh hook for the open dialog. */
    private var editingAvatarEmail: String? = null
    private var editProfileAvatarRefresh: (() -> Unit)? = null

    internal val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val email = editingAvatarEmail ?: return@registerForActivityResult
        if (uri != null) showAvatarCropDialog(uri, email)
    }
    internal lateinit var accentColorPreview: View
    internal lateinit var accentColorRow: LinearLayout
    internal var currentAccentColor: String = "#3D8BFD"

    /** User labels (ordered) + drawer menu ids assigned to each label keyword. */
    internal val labels = mutableListOf<EmailLabel>()
    internal val accountLabelsCache = mutableMapOf<String, List<EmailLabel>>()
    internal val labelNavIds = linkedMapOf<Int, String>()
    internal lateinit var detailLabelRowView: LinearLayout
    internal val isDetailLabelRowViewInit: Boolean get() = ::detailLabelRowView.isInitialized
    internal fun debugTs(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    internal var labelDragHelper: ItemTouchHelper? = null

    internal val categoryOrder =
            mutableListOf(
                    R.id.nav_inbox,
                    R.id.nav_favourite,
                    R.id.nav_archive,
                    R.id.nav_sent,
                    R.id.nav_drafts,
                    R.id.nav_spam,
                    R.id.nav_trash
            )
    internal val categoryNames = mutableMapOf<Int, String>()
    internal val emails = mutableListOf<DisplayEmail>()
    // Current page size for the visible folder. Grows by PAGE_SIZE on scroll-to-bottom.
    internal var emailLimit = JMapClient.DEFAULT_EMAIL_LIMIT
    // True while a "load more" fetch is in flight, to avoid stacking requests.
    internal var isLoadingMore = false
    internal lateinit var emailAdapter: EmailAdapter
    internal lateinit var jmapClient: JMapClient
    private lateinit var emailCache: EmailCache
    internal var connectedAccount: JMapClient.ConnectedAccount? = null
    internal val savedAccounts = mutableListOf<AccountEntry>()
    internal var currentAccountEmail: String? = null
    internal var selectedFolder: Int = R.id.nav_inbox
    internal var prevUpdateFolder: Int = -1
    internal val folderCache = mutableMapOf<Int, List<DisplayEmail>>()
    private var syncJob: Job? = null
    internal var currentSettingsSection: SettingsSection = SettingsSection.ROOT
    internal var currentTheme: String = "gray"
    internal val selectedEmails = mutableSetOf<String>()
    internal val baseEmails = mutableListOf<DisplayEmail>() // unfiltered list for search

    // --- Conversation threading (chat-style) ---
    // threadKey -> all member emails (newest first). Built by buildThreadedView().
    internal val threadMembers = LinkedHashMap<String, List<DisplayEmail>>()
    // Expanded thread keys: their child messages are shown indented under the head row.
    internal val expandedThreads = mutableSetOf<String>()
    // Per-thread cap on how many child messages are currently revealed. Grows by
    // THREAD_PAGE each time the user taps the "+N more" row.
    internal val threadChildLimit = HashMap<String, Int>()

    /**
     * Stable grouping key. Conversations are grouped by normalized subject (Re:/Fwd:
     * prefixes stripped) so a forwarded/replied chain collapses into one chat even when
     * the server hands out a fresh threadId per message. Falls back to threadId, then to
     * a per-id singleton for blank subjects.
     */

    internal val replyForwardPrefix =
        Regex("^\\s*(re|fwd|fw|r|i|aw|sv|antw)\\s*(\\[\\d+])?\\s*:\\s*", RegexOption.IGNORE_CASE)

    /** Lowercased subject with leading reply/forward markers and surrounding noise removed. */

    /**
     * Collapses [full] (newest-first) into a chat-style threaded list: one head row per
     * conversation, with the other messages emitted right after it only when expanded.
     * Bakes per-row thread state (count/head/child/key) onto each DisplayEmail so DiffUtil
     * rebinds rows when threading changes, and rebuilds [threadMembers] as a side effect.
     */

    /** Toggles a conversation's expanded state and rebuilds the visible list. */

    /** Reveals the next page of hidden messages in an expanded conversation. */
    // Pending request from a widget tap: open this email once its account's data is loaded.
    internal var pendingWidgetEmailId: String? = null
    internal var pendingWidgetAccount: String? = null
    internal var widgetSwitchAttempted = false
    // Pending request from the calendar widget: open calendar (WEEK) once the UI/session is ready.
    private var pendingOpenCalendar = false
    private var pendingCalendarNewEvent = false
    private var pendingCalendarEventStart = 0L
    internal var isSearchActive = false
    private var wasImeVisible = false
    internal lateinit var selectionBarContainer: LinearLayout
    internal lateinit var selectionCountText: TextView
    internal lateinit var selectionCloseBtn: ImageView
    internal lateinit var selectionArchiveBtn: ImageView
    internal lateinit var selectionDeleteBtn: ImageView
    internal lateinit var selectionReadBtn: ImageView
    internal lateinit var selectionMoreBtn: ImageView
    internal lateinit var searchInput: EditText
    internal lateinit var searchClearBtn: ImageView
    private val pushMessageReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == UnifiedPushService.ACTION_PUSH_MESSAGE_RECEIVED &&
                                    connectedAccount != null
                    ) {
                        refreshInboxNow()
                    }
                }
            }

    internal val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                    isGranted: Boolean ->
                if (isGranted) {
                    Log.d(TAG, "Notification permission granted")
                } else {
                    Log.w(TAG, "Notification permission denied")
                }
                onboardingPermRefresh?.invoke()
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (API 35) enforces edge-to-edge by default; opt out to keep
        // the existing layout which does not handle system bar insets manually.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        FaviconRepository.init(cacheDir)

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        toolbar = findViewById(R.id.toolbar)
        onboardingContainer = findViewById(R.id.onboardingContainer)
        onboardingBottomBar = findViewById(R.id.onboardingBottomBar)
        onboardingPager = findViewById(R.id.onboardingPager)
        onboardingNextFab = findViewById(R.id.onboardingNextFab)
        onboardingDots = findViewById(R.id.onboardingDots)
        loginContainer = findViewById(R.id.loginContainer)
        loginBackBtn = findViewById(R.id.loginBackBtn)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        settingsContainer = findViewById(R.id.settingsContainer)
        settingsMenuContainer = findViewById(R.id.settingsMenuContainer)
        settingsGeneralContainer = findViewById(R.id.settingsGeneralContainer)
        settingsGeneralHeader = findViewById(R.id.settingsGeneralHeader)
        settingsGeneralContent = findViewById(R.id.settingsGeneralContent)
        settingsGeneralChevron = findViewById(R.id.settingsGeneralChevron)
        settingsLabelsContainer = findViewById(R.id.settingsLabelsContainer)
        settingsLabelsHeader = findViewById(R.id.settingsLabelsHeader)
        settingsLabelsContent = findViewById(R.id.settingsLabelsContent)
        settingsLabelsChevron = findViewById(R.id.settingsLabelsChevron)
        settingsSwipeContainer = findViewById(R.id.settingsSwipeContainer)
        settingsUnifiedPushContainer = findViewById(R.id.settingsUnifiedPushContainer)
        settingsUnifiedPushHeader = findViewById(R.id.settingsUnifiedPushHeader)
        settingsUnifiedPushContent = findViewById(R.id.settingsUnifiedPushContent)
        settingsUnifiedPushChevron = findViewById(R.id.settingsUnifiedPushChevron)

        settingsThemeContainer = findViewById(R.id.settingsThemeContainer)
        settingsThemeHeader = findViewById(R.id.settingsThemeHeader)
        settingsThemeContent = findViewById(R.id.settingsThemeContent)
        settingsThemeChevron = findViewById(R.id.settingsThemeChevron)
        settingsInfoRow = findViewById(R.id.settingsInfoRow)
        settingsInfoIcon = findViewById(R.id.settingsInfoIcon)
        settingsInfoArrow = findViewById(R.id.settingsInfoArrow)
        loadImagesSwitch = findViewById(R.id.loadImagesSwitch)
        loadFaviconsSwitch = findViewById(R.id.loadFaviconsSwitch)
        themeDropdown = findViewById(R.id.themeDropdown)
        themeDropdownText = findViewById(R.id.themeDropdownText)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        loginButton = findViewById(R.id.loginButton)
        mailboxContainer = findViewById(R.id.mailboxContainer)
        emailsRecyclerView = findViewById(R.id.emailsRecyclerView)
        mailSwipeRefresh = findViewById(R.id.mailSwipeRefresh)
        fabCompose = findViewById(R.id.fabCompose)
        composeContainer = findViewById(R.id.composeContainer)
        composeSendButton = findViewById(R.id.composeSendButton)
        composeFromLabel = findViewById(R.id.composeFromLabel)
        composeFromText = findViewById(R.id.composeFromText)
        composeToChipsGroup = findViewById(R.id.composeToChipsGroup)
        composeCcChipsGroup = findViewById(R.id.composeCcChipsGroup)
        composeBccChipsGroup = findViewById(R.id.composeBccChipsGroup)
        composeCategoryTabs = findViewById(R.id.composeCategoryTabs)
        composeToInput = findViewById(R.id.composeToInput)
        composeSubjectInput = findViewById(R.id.composeSubjectInput)
        composeBodyInput = findViewById(R.id.composeBodyInput)
        formatToolbar = findViewById(R.id.formatToolbar)
        formatToolbarRow = findViewById(R.id.formatToolbarRow)
        composeAttachButton = findViewById(R.id.composeAttachButton)
        attachmentChipScroll = findViewById(R.id.attachmentChipScroll)
        attachmentChipContainer = findViewById(R.id.attachmentChipContainer)
        attachmentChipDivider = findViewById(R.id.attachmentChipDivider)
        unifiedPushSwitch = findViewById(R.id.unifiedPushSwitch)
        sseSwitch = findViewById(R.id.sseSwitch)
        val frameLayout =
                FrameLayout(this).apply {
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            )
                }
        mailSwipeRefresh.removeView(emailsRecyclerView)
        frameLayout.addView(emailsRecyclerView)

        emptyStateView =
                TextView(this).apply {
                    text = "Nothing here ¯\\_(ツ)_/¯"
                    textSize = 18f
                    setTextColor(Color.GRAY)
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    Gravity.CENTER
                            )
                }
        frameLayout.addView(emptyStateView)
        mailSwipeRefresh.addView(frameLayout)

        status =
                findViewById<TextView>(R.id.status).apply {
                    text = getString(R.string.status_initial)
                }
        customTopBar = findViewById(R.id.customTopBar)
        topBarAccentArea = findViewById(R.id.topBarAccentArea)
        folderLabel = findViewById(R.id.folderLabel)
        searchBarMenuIcon = findViewById(R.id.searchBarMenuIcon)
        searchBarTitle = findViewById(R.id.searchBarTitle)
        searchBarContainer = findViewById(R.id.searchBarContainer)
        selectionBarContainer = findViewById(R.id.selectionBarContainer)
        selectionCountText = findViewById(R.id.selectionCountText)
        selectionCloseBtn = findViewById(R.id.selectionCloseBtn)
        selectionArchiveBtn = findViewById(R.id.selectionArchiveBtn)
        selectionDeleteBtn = findViewById(R.id.selectionDeleteBtn)
        selectionReadBtn = findViewById(R.id.selectionReadBtn)
        selectionMoreBtn = findViewById(R.id.selectionMoreBtn)
        searchInput = findViewById(R.id.searchInput)
        searchChipsScroll = findViewById(R.id.searchChipsScroll)
        searchChipsRow = findViewById(R.id.searchChipsRow)
        searchClearBtn = findViewById(R.id.searchClearBtn)
        swipeRightDropdown = findViewById(R.id.swipeRightDropdown)
        swipeLeftDropdown = findViewById(R.id.swipeLeftDropdown)
        swipeRightDropdownText = findViewById(R.id.swipeRightDropdownText)
        swipeLeftDropdownText = findViewById(R.id.swipeLeftDropdownText)
        settingsCalProviderDropdown = findViewById(R.id.settingsCalProviderDropdown)
        settingsCalProviderText = findViewById(R.id.settingsCalProviderText)
        calendarEnabledSwitch = findViewById(R.id.calendarEnabledSwitch)
        settingsCalAddProviderButton = findViewById(R.id.settingsCalAddProviderRow)
        topBarSendButton = findViewById(R.id.topBarSendButton)
        quoteIndicatorRow = findViewById(R.id.quoteIndicatorRow)
        quoteIndicatorLabel = findViewById(R.id.quoteIndicatorLabel)
        quoteIndicatorRemove = findViewById(R.id.quoteIndicatorRemove)
        quoteIndicatorDivider = findViewById(R.id.quoteIndicatorDivider)
        settingsEditLabelsButton = findViewById(R.id.settingsEditLabelsButton)
        quoteIndicatorRemove.setOnClickListener { clearPendingQuote() }
        val drawerHeader = navigationView.getHeaderView(0)
        drawerAccountName = drawerHeader.findViewById(R.id.drawerAccountName)
        drawerAccountEmail = drawerHeader.findViewById(R.id.drawerAccountEmail)
        drawerAccountAvatar = drawerHeader.findViewById(R.id.drawerAccountAvatar)
        drawerAccountRow = drawerHeader.findViewById(R.id.drawerAccountRow)
        drawerAccountArrow = drawerHeader.findViewById(R.id.drawerAccountArrow)
        drawerAccountsList = drawerHeader.findViewById(R.id.drawerAccountsList)
        val drawerVersionText = findViewById<TextView>(R.id.drawerVersionText)
        drawerVersionText.text = "JMAPJolt v${BuildConfig.VERSION_NAME}"
        accentColorPreview = findViewById(R.id.accentColorPreview)
        accentColorRow = findViewById(R.id.accentColorRow)

        jmapClient = JMapClient(this)
        emailCache = EmailCache(filesDir)

        setSupportActionBar(toolbar)
        drawerToggle =
                ActionBarDrawerToggle(
                        this,
                        drawerLayout,
                        toolbar,
                        R.string.drawer_open,
                        R.string.drawer_close
                )
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.setToolbarNavigationClickListener { handleNavigationClick() }
        searchBarMenuIcon.setOnClickListener {
            if (drawerToggle.isDrawerIndicatorEnabled) drawerLayout.openDrawer(GravityCompat.START)
            else handleNavigationClick()
        }

        setupEmailDetailView()
        setupComposeView()
        loadCategoryPreferences()
        loadLabels()
        setupAdapters()
        setupSwipeSpinners()
        setupThemeSpinner()
        loadThemePreference()
        loadUnifiedPushPreferences()
        loadGeneralPreferences()
        rebuildDrawerMenu()
        bindSettingsActions()
        bindDrawerNavigation()
        bindSettingsMenuNavigation()
        bindPullToRefresh()
        loadAccounts()
        applyTheme()
        handleMailtoIntent(intent)
        handleWidgetIntent(intent)
        handleCalendarIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    composeContainer.visibility == View.VISIBLE -> attemptLeaveCompose()
                    drawerLayout.isDrawerOpen(GravityCompat.START) ->
                        drawerLayout.closeDrawer(GravityCompat.START)
                    calendarPanelView?.visibility == View.VISIBLE ->
                        if (calendarPanelView?.onBackPressed() != true) showMailboxScreen()
                    selectedEmails.isNotEmpty() -> clearSelection()
                    isSearchActive -> deactivateSearch()
                    isShowingEmailDetail -> closeEmailDetail()
                    settingsContainer.visibility == View.VISIBLE -> {
                        if (currentSettingsSection != SettingsSection.ROOT) attemptLeaveSettingsSubmenu()
                        else showMailboxScreen()
                    }
                    loginContainer.visibility == View.VISIBLE -> showOnboarding()
                    onboardingContainer.visibility == View.VISIBLE && onboardingPager.currentItem > 0 ->
                        onboardingPager.setCurrentItem(onboardingPager.currentItem - 1, true)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        // Closing the keyboard (single back press) also exits search: chips and
        // input disappear without needing a second back press.
        // Note: an OnApplyWindowInsetsListener on an inner view never fires here
        // because the window is not edge-to-edge; root insets polled on layout
        // changes are reliable regardless of fitsSystemWindows.
        drawerLayout.viewTreeObserver.addOnGlobalLayoutListener {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(drawerLayout)
            val imeVisible =
                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            if (wasImeVisible && !imeVisible && isSearchActive) {
                // Global layout fires mid-layout-pass: mutating the RecyclerView
                // adapter here corrupts child state ("Called attach on a child
                // which is not detached"). Defer past the layout pass.
                drawerLayout.post { if (isSearchActive) deactivateSearch() }
            }
            wasImeVisible = imeVisible
        }

        setupOnboardingPager()
        drawerAccountRow.setOnClickListener {
            val open = drawerAccountsList.visibility != View.VISIBLE
            drawerAccountArrow.animate().rotation(if (open) 180f else 0f).setDuration(200).start()
            if (open) {
                // Expand like the settings accordions: grow + fade.
                drawerAccountsList.visibility = View.VISIBLE
                drawerAccountsList.measure(
                    View.MeasureSpec.makeMeasureSpec(navigationView.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val target = drawerAccountsList.measuredHeight
                drawerAccountsList.layoutParams.height = 0
                drawerAccountsList.alpha = 0f
                android.animation.ValueAnimator.ofInt(0, target).apply {
                    duration = 300
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    addUpdateListener {
                        drawerAccountsList.layoutParams.height = it.animatedValue as Int
                        // Fade in faster than the height grows so content lands settled.
                        drawerAccountsList.alpha = kotlin.math.min(1f, it.animatedFraction * 1.6f)
                        drawerAccountsList.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            drawerAccountsList.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            drawerAccountsList.alpha = 1f
                            drawerAccountsList.requestLayout()
                        }
                    })
                    start()
                }
            } else {
                val start = drawerAccountsList.height
                android.animation.ValueAnimator.ofInt(start, 0).apply {
                    duration = 240
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                    addUpdateListener {
                        drawerAccountsList.layoutParams.height = it.animatedValue as Int
                        drawerAccountsList.alpha = 1f - it.animatedFraction
                        drawerAccountsList.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            drawerAccountsList.visibility = View.GONE
                            drawerAccountsList.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            drawerAccountsList.alpha = 1f
                            drawerAccountsList.requestLayout()
                        }
                    })
                    start()
                }
            }
        }
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                drawerAccountsList.visibility = View.GONE
                drawerAccountArrow.rotation = 0f
            }
        })

        if (!restoreLastAccountSession()) {
            if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_WELCOME_SHOWN, false)) {
                showLoginScreen()
            } else {
                showOnboarding()
            }
        } else if (unifiedPushSwitch.isChecked) {
            // Restored a saved session: ensure the periodic fallback worker is
            // active (it may have been cancelled by a past push registration).
            EmailSyncWorker.schedule(this)
        }
        // After the session restore decided the initial screen, honor a pending calendar
        // widget tap so it lands on the calendar (WEEK) instead of the inbox.
        applyPendingCalendarIntent()
        emailInput.addTextChangedListener(simpleWatcher)
        passwordInput.addTextChangedListener(simpleWatcher)
        serverUrlInput.addTextChangedListener(simpleWatcher)
        loginButton.setOnClickListener { connectAndOpenMailbox() }
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                pushMessageReceiver,
                IntentFilter(UnifiedPushService.ACTION_PUSH_MESSAGE_RECEIVED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateFormState()
        // Notification permission is only requested from the onboarding permission screen,
        // never automatically on launch.
    }

    private val simpleWatcher =
            object : TextWatcher {
                override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                ) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                        updateFormState()
                override fun afterTextChanged(s: Editable?) = Unit
            }

    internal fun completeOnboardingToLogin() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WELCOME_SHOWN, true)
                .apply()
        loginFromOnboarding = true
        showLoginScreen()
    }

    internal var isShowingEmailDetail = false

    /** Calendar UI hosted in the content area so the app drawer stays available over it. */
    internal var calendarPanelView: CalendarPanel? = null

    /** Opens the app navigation drawer (used by the calendar panel's hamburger). */
    internal fun openMainDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    /** Launches the .ics import picker and refreshes the calendar panel on return. */
    internal fun launchCalendarIcsImport() {
        runCatching { importIcsLauncher.launch(arrayOf("text/calendar", "*/*")) }
    }

    /** Shows the calendar inside MainActivity (keeps the real drawer). */
    internal fun showCalendarScreen() {
        if (composeContainer.visibility == View.VISIBLE) hideCompose()
        onboardingContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        loginBackBtn.visibility = View.GONE
        mailboxContainer.visibility = View.GONE
        settingsContainer.visibility = View.GONE
        emailDetailContainer.visibility = View.GONE
        fabCompose.visibility = View.GONE
        customTopBar.visibility = View.GONE
        isShowingEmailDetail = false
        val panel = calendarPanelView ?: CalendarPanel(this).also { p ->
            calendarPanelView = p
            val parent = mailboxContainer.parent as android.view.ViewGroup
            parent.addView(p, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
        panel.visibility = View.VISIBLE
        panel.bringToFront()
        panel.refresh()
        panel.onShown()
        navigationView.post { rebuildDrawerMenu() }
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    internal fun hideCalendarScreen() {
        calendarPanelView?.visibility = View.GONE
    }

    /** Id of the draft currently being edited, so it can be replaced (destroyed) on save/send. */
    internal var editingDraftId: String? = null

    /** Optimistic favorite state per email id, kept until the server sync reflects it. */
    internal val optimisticFavorite = mutableMapOf<String, Boolean>()

    /** Mailboxes cached so the "Move to" sheet opens instantly without a network round-trip. */
    internal var mailboxCache: List<JMapClient.MailboxInfo>? = null

    internal fun showMailboxScreen(skipRefresh: Boolean = false) {
        onboardingContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        loginBackBtn.visibility = View.GONE
        hideCalendarScreen()
        mailboxContainer.visibility = View.VISIBLE
        mailboxContainer.animateScreenInBack()
        emailDetailContainer.visibility = View.GONE
        mailSwipeRefresh.visibility = View.VISIBLE
        fabCompose.animateFabIn()
        settingsContainer.visibility = View.GONE
        customTopBar.visibility = View.VISIBLE
        isShowingEmailDetail = false
        currentSettingsSection = SettingsSection.ROOT
        invalidateOptionsMenu()
        setDrawerIndicator(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        drawerToggle.syncState()
        applyNavIconTint(getOnAccentColor())
        updateTopBarState()
        rebuildDrawerMenu()
        if (!skipRefresh) applyFolderFilterAndRefresh()

    }




    /** Lazily builds the sliding preview panel (a second WebView) used during detail swipes. */

    /** Loads [target]'s content into the preview (cached body, or the shimmer skeleton). */

    /** Content follows the finger; the adjacent email slides in alongside it (no empty gap). */

    /** Overflow menu (3 dots) on the detail header: actions that used to be inline icons. */

    /** Moves an archived email back to the inbox (detail-view counterpart of swipe unarchive). */

    /** Re-syncs body inset and the swipe zone with the (content-dependent) header height. */

    /** "to me ▾" tap: floating popup card with full addresses; tap anywhere outside to dismiss. */

    /** Heuristic: true when the body carries real HTML markup (full document or fragment). */

    /**
     * Collapses deeply-nested quote/forward chains behind a no-JS <details> toggle.
     * Once the nesting of quote containers (`.quoted-html-island` divs or <blockquote>)
     * exceeds [threshold], the container that crosses the threshold — and everything inside
     * it — is wrapped in a collapsible <details class="jj-quote-collapse"> element so long
     * forward chains don't flood the view. Pure HTML/CSS, no JavaScript required.
     *
     * Only `div` and `blockquote` elements are balanced (they form quote nesting); void and
     * other tags are ignored for depth tracking. Insertions are applied right-to-left so
     * earlier indices stay valid.
     */

    /** Captures a widget tap so the target email opens once its data is available. */

    /** Navigates to the inbox the inbox-widget header represents (single account or unified). */

    /** Opens a pending widget email when its account is active and the message is loaded. */

    internal fun showEmailDetail(email: DisplayEmail, fromSwipe: Boolean = false) {
        if (!fromSwipe) {
            mailSwipeRefresh.visibility = View.GONE
            fabCompose.animateFabOut()
            emailDetailContainer.visibility = View.VISIBLE
            emailDetailContainer.animateScreenIn()
        }
        isShowingEmailDetail = true
        currentDetailEmail = email
        updateDetailStarIcon(email.isFavorite)
        // Reset the auto-hide action row to fully visible on open.
        detailBarHidden = false
        detailHeaderRow.animate().cancel()
        detailHeaderRow.visibility = View.VISIBLE
        detailHeaderRow.translationY = 0f
        detailHeaderRow.alpha = 1f
        detailBody.setPadding(0, detailBarHeight, 0, 0)
        setDrawerIndicator(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawerToggle.syncState()
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
        applyNavIconTint(getOnAccentColor())
        val (toolbarColor, textColor, secondaryTextColor) =
                when (currentTheme) {
                    "light" -> Triple("#F5F5F5", "#212121", "#757575")
                    "oled" -> Triple("#000000", "#FFFFFF", "#BDBDBD")
                    "violet" -> Triple("#140B22", "#FFFFFF", "#BDBDBD")
                    else -> Triple("#2A2A2A", "#FFFFFF", "#BDBDBD")
                }

        detailHeaderRow.setBackgroundColor(toolbarColor.toColorInt())
        detailSubject.text = email.subject.ifBlank { "(no subject)" }
        detailSubject.setTextColor(textColor.toColorInt())
        detailFrom.setTextColor(textColor.toColorInt())
        detailFrom.text = email.from.ifBlank { email.fromEmail }
        detailDate.text = if (email.receivedAt > 0) formatRelativeDate(email.receivedAt) else ""
        detailDate.setTextColor(secondaryTextColor.toColorInt())
        val toLabel = when {
            email.toEmail.isBlank() -> "to me"
            email.toEmail.equals(email.accountEmail, ignoreCase = true) -> "to me"
            else -> "to ${email.toEmail}"
        }
        detailToText.text = "$toLabel  ▾"
        detailToText.setTextColor(secondaryTextColor.toColorInt())

        // Tint the pinned action icons to contrast the header; star reflects favourite state.
        val actionTint = ColorStateList.valueOf(textColor.toColorInt())
        listOf(detailReplyButton, detailForwardButton, detailArchiveButton,
               detailTrashButton, detailMoveButton, detailMoreButton).forEach { it.imageTintList = actionTint }
        updateDetailStarIcon(email.isFavorite)
        updateDetailLabelIcon()

        // Header height is content-dependent now (subject wraps): sync the body
        // inset and the swipe-from-header zone once it is laid out.
        syncDetailHeaderHeight()

        // Remove previous attachment footer if present (detailBody: 0=WebView, 1=spacer, 2=attRow)
        if (detailBody.childCount > 2) detailBody.removeViewAt(2)

        val account = resolveAccountFor(email)
        if (email.attachments.isNotEmpty() && account != null) {
            val attRow = buildEmailAttachmentRow(email.attachments, account)
            detailBody.addView(attRow)
        }

        // Email view background = the home/inbox background per theme, so the whole screen matches.
        val wvBgHex = when (currentTheme) {
            "light"  -> "#F6F6F8"
            "oled"   -> "#000000"
            "violet" -> "#160E24"
            else     -> "#212126"
        }
        val wvBgInt = android.graphics.Color.parseColor(wvBgHex)
        // Paint the whole scroll area (webview + spacer + attachment footer) with one colour so
        // the screen reads as a single email view. The webview itself is transparent so its own
        // (possibly skeleton) backdrop never shows a different shade.
        detailWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        detailScroll.setBackgroundColor(wvBgInt)
        detailBody.setBackgroundColor(wvBgInt)
        // Show cached body immediately (zero latency) or a shimmer skeleton while fetching.
        val bodyAvailableNow = email.fullBody.isNotBlank()
        if (bodyAvailableNow) {
            detailWebView.loadDataWithBaseURL("https://jmapjolt.invalid/email/",buildHtmlContent(email.fullBody), "text/html", "UTF-8", null)
        } else {
            detailWebView.loadDataWithBaseURL("https://jmapjolt.invalid/email/",buildSkeletonHtml(), "text/html", "UTF-8", null)
        }
        // Auto-load images based on preference
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val loadImages = prefs.getBoolean("load_images", false)
        detailWebView.settings.blockNetworkImage = !loadImages

        lifecycleScope.launch {
            try {
                val account = resolveAccountFor(email)
                val needsFetch = email.fullBody.isBlank() || email.attachments.isEmpty()
                var displayEmail = email
                if (needsFetch && account != null) {
                    val fresh = jmapClient.fetchEmailsById(account, listOf(email.id)).firstOrNull()
                    if (fresh != null) {
                        val updated = email.copy(
                            fullBody = if (fresh.fullBody.isNotBlank()) fresh.fullBody else email.fullBody,
                            attachments = fresh.attachments
                        )
                        displayEmail = updated
                        val idx = emails.indexOfFirst { it.id == email.id }
                        if (idx >= 0) {
                            emails[idx] = updated
                            val bi = baseEmails.indexOfFirst { it.id == email.id }
                            if (bi >= 0) baseEmails[bi] = updated
                            saveEmailCache()
                        }
                        // Refresh attachment footer
                        if (detailBody.childCount > 2) detailBody.removeViewAt(2)
                        if (updated.attachments.isNotEmpty()) {
                            val attRow = buildEmailAttachmentRow(updated.attachments, account)
                            detailBody.addView(attRow)
                        }
                    }
                }
                currentDetailEmail = displayEmail
                // Only render if body was skeleton (not already rendered synchronously above).
                if (!bodyAvailableNow) {
                    val htmlContent = buildHtmlContent(displayEmail.fullBody)
                    detailWebView.loadDataWithBaseURL("https://jmapjolt.invalid/email/",htmlContent, "text/html", "UTF-8", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load email HTML", e)
            }
        }

        // No sender label in the top bar: the pinned header already shows it.
        updateCustomTopBar("", inMailbox = false)
        searchChipsScroll.visibility = View.GONE


        if (!email.seen) {
            val account = connectedAccount
            if (account != null) {
                // 1. Optimistic local UI update
                email.seen = true
                emailAdapter.notifyDataSetChanged()
                saveEmailCache()

                // 2. Asynchronous JMAP server update
                lifecycleScope.launch {
                    try {
                        jmapClient.setSeen(account, email.id, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark email seen on server", e)
                    }
                }
            }
        }

        // Prefetch adjacent emails so swipe navigation loads instantly.
        val curIdx = emails.indexOfFirst { it.id == email.id }
        if (curIdx >= 0) {
            emails.getOrNull(curIdx - 1)?.let { prefetchEmailBody(it) }
            emails.getOrNull(curIdx + 1)?.let { prefetchEmailBody(it) }
        }
    }



    /** Requests READ/WRITE_CALENDAR; invokes [onResult] once the user responds. */

    internal var calendarPermissionCallback: (() -> Unit)? = null
    internal val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> calendarPermissionCallback?.invoke(); calendarPermissionCallback = null }

    /** Reflects the selected calendar provider in the dropdown text, hint, add button + account. */

    /** Handles a provider switch: warn here (not in the calendar tab) when the choice can't sync. */

    /** App-styled bottom in-app message (matches the snackbars used elsewhere). */




    /**
     * Theme-aware snackbar with an optional action button that can show a leading icon.
     * Background and text colours follow the active theme; the action uses the accent colour.
     */

    /** Extension-visible wrapper (label helpers live in LabelHelper.kt). */


    /**
     * Grows the page size when the user scrolls near the bottom, so more emails
     * load on demand instead of being capped at the first page. The periodic sync
     * loop refetches the folder with the larger [emailLimit].
     */

    /** Long-press drag to reorder label rows inside the drawer's internal RecyclerView. */



    /** Cache bucket for a folder, scoped per account (or "unified" for the merged inbox). */

    /** Display the persisted snapshot for a folder before the network responds. */

    /** Persist a freshly fetched folder snapshot for offline viewing. */



    /** Collapses the scope chips bar height + fades it, so it retracts up behind the top bar. */

    // Search scope chips: label -> drawer folder id (null = search everywhere).
    internal val searchScopes = listOf<Pair<String, Int?>>(
        "All" to null,
        "Inbox" to R.id.nav_inbox,
        "Favorite" to R.id.nav_favourite,
        "Archive" to R.id.nav_archive,
        "Sent" to R.id.nav_sent,
        "Trash" to R.id.nav_trash
    )


    /** Emails to search through, based on the selected scope chip. */


    /**
     * Removes the given emails from the visible list (and the search base list) with a
     * per-row removal animation. Call any clearSelection()/ActionMode.finish() BEFORE this,
     * since those trigger a full notifyDataSetChanged that would cancel the animation.
     */

    /** Overlays pending favorite toggles on freshly synced data until the server reflects them. */

    /** Inserts a just-saved draft into the Drafts list immediately, before the next sync. */
    internal fun insertOptimisticDraft(
        to: String,
        subject: String,
        bodyHtml: String,
        accountEmail: String,
        removeId: String?
    ) {
        @Suppress("DEPRECATION")
        val plain = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY)
        else
            Html.fromHtml(bodyHtml)).toString().trim()

        val draft = DisplayEmail(
            id = "local-draft-" + System.currentTimeMillis(),
            subject = if (subject.isBlank()) "(No Subject)" else subject,
            from = accountEmail,
            fromEmail = accountEmail,
            preview = plain.take(140),
            fullBody = bodyHtml,
            seen = true,
            isFavorite = false,
            receivedAt = System.currentTimeMillis(),
            toEmail = to
        )
        val current = (folderCache[R.id.nav_drafts] ?: emptyList()).toMutableList()
        if (removeId != null) current.removeAll { it.id == removeId }
        current.add(0, draft)
        folderCache[R.id.nav_drafts] = current
        if (selectedFolder == R.id.nav_drafts) updateEmailsList(current)
    }

    /** Asks for confirmation, then permanently destroys emails (used in Trash). */
    internal fun confirmPermanentDelete(account: JMapClient.ConnectedAccount, ids: List<String>) {
        showThemedConfirmDialog(
            title = "Delete permanently",
            message = if (ids.size == 1)
                "Permanently delete this email? This can't be undone."
            else
                "Permanently delete ${ids.size} emails? This can't be undone.",
            confirmLabel = "Delete",
            isDangerous = true
        ) {
            clearSelection()
            removeEmailsAnimated(ids)
            folderCache[R.id.nav_trash] = emails.toList()
            saveEmailCache()
            lifecycleScope.launch {
                ids.forEach {
                    try { jmapClient.destroyEmail(account, it) }
                    catch (e: Exception) { Log.e(TAG, "destroyEmail failed", e) }
                }
            }
        }
    }

    internal fun performAction(action: String) {
        val account = connectedAccount ?: return
        val ids = selectedEmails.toList()
        if (ids.isEmpty()) return

        if (selectedFolder == R.id.nav_drafts && (action == "archive" || action == "toggleRead")) {
            Snackbar.make(drawerLayout, "Not available for drafts", Snackbar.LENGTH_SHORT).show()
            clearSelection()
            return
        }

        if (action == "delete" && selectedFolder == R.id.nav_trash) {
            confirmPermanentDelete(account, ids)
            return
        }

        when (action) {
            "archive", "delete" -> {
                // Archiving from Favourites keeps the email flagged, so it must stay
                // visible there (an email can be both favourited and archived).
                if (action == "archive" && selectedFolder == R.id.nav_favourite) {
                    clearSelection()
                    emailAdapter.notifyDataSetChanged()
                    Snackbar.make(drawerLayout, "Archived", Snackbar.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        try {
                            ids.forEach { id ->
                                val acc = resolveAccountForId(id) ?: return@forEach
                                val archiveId = resolveOrCreateArchive(acc) ?: return@forEach
                                jmapClient.setMailbox(acc, id, archiveId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Archive from favourites failed", e)
                        }
                    }
                    return
                }
                val movedEmails = emails.filter { it.id in ids }
                // Resolve accounts before removeEmailsAnimated wipes baseEmails
                val accountsById = movedEmails.associate { it.id to (resolveAccountFor(it) ?: connectedAccount) }
                clearSelection()
                removeEmailsAnimated(ids)
                val targetNavId = if (action == "archive") R.id.nav_archive else R.id.nav_trash
                movedEmails.forEach { updateFolderCachesForMove(it, targetNavId) }
                saveEmailCache()

                lifecycleScope.launch {
                    try {
                        when (action) {
                            "archive" -> ids.forEach { id ->
                                val acc = accountsById[id] ?: return@forEach
                                val archiveId = resolveOrCreateArchive(acc) ?: return@forEach
                                jmapClient.setMailbox(acc, id, archiveId)
                            }
                            "delete" -> ids.forEach { id ->
                                val acc = accountsById[id] ?: return@forEach
                                val trashId = resolveMailboxIdByRole(acc, "trash") ?: return@forEach
                                jmapClient.setMailbox(acc, id, trashId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Action failed", e)
                    }
                }
            }
            "unarchive" -> {
                val movedEmails = emails.filter { it.id in ids }
                // Resolve accounts before removeEmailsAnimated wipes baseEmails
                val accountsById = movedEmails.associate { it.id to (resolveAccountFor(it) ?: connectedAccount) }
                clearSelection()
                removeEmailsAnimated(ids)
                movedEmails.forEach { updateFolderCachesForInbox(it) }
                saveEmailCache()
                lifecycleScope.launch {
                    try {
                        ids.forEach { id ->
                            val acc = accountsById[id] ?: return@forEach
                            val inboxId = resolveMailboxIdByRole(acc, "inbox") ?: return@forEach
                            jmapClient.setMailbox(acc, id, inboxId)
                            BackgroundEmailSyncReceiver.addToBaseline(this@MainActivity, acc.email, listOf(id))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Unarchive failed", e)
                    }
                }
            }
            "toggleRead" -> {
                val allSeen = ids.all { id -> baseEmails.find { it.id == id }?.seen == true }
                val newState = !allSeen
                emails.forEach { if (it.id in ids) it.seen = newState }
                baseEmails.forEach { if (it.id in ids) it.seen = newState }
                clearSelection()
                emailAdapter.notifyDataSetChanged()
                saveEmailCache()
                lifecycleScope.launch {
                    try {
                        ids.forEach { id ->
                            val acc = resolveAccountForId(id) ?: return@forEach
                            jmapClient.setSeen(acc, id, newState)
                        }
                    }
                    catch (e: Exception) { Log.e(TAG, "toggleRead failed", e) }
                }
            }
            "more" -> showMoreOptionsPopup(null)
        }
    }

    /**
     * Inserts an email into a receivedAt-descending list at its correct chronological
     * position, so optimistic updates match the order the server sync will produce
     * (no visible "jump" once the background sync lands).
     */
    private fun insertSortedByDate(
        list: List<DisplayEmail>,
        email: DisplayEmail
    ): List<DisplayEmail> {
        val result = ArrayList<DisplayEmail>(list.size + 1)
        var inserted = false
        for (e in list) {
            if (!inserted && email.receivedAt >= e.receivedAt) {
                result.add(email)
                inserted = true
            }
            result.add(e)
        }
        if (!inserted) result.add(email)
        return result
    }

    internal fun updateFolderCachesForFavorite(email: DisplayEmail, isFavorite: Boolean) {
        val favKey = R.id.nav_favourite
        if (isFavorite) {
            // Only add to favourites cache when the email is not from Trash
            if (selectedFolder != R.id.nav_trash) {
                val current = folderCache[favKey]
                if (current != null && current.none { it.id == email.id }) {
                    folderCache[favKey] = insertSortedByDate(current, email.copy(isFavorite = true))
                }
            }
        } else {
            val current = folderCache[favKey]
            if (current != null) {
                folderCache[favKey] = current.filter { it.id != email.id }
            }
        }
    }

    /** Moves an email back from Archive to the Inbox cache, keeping date order. */
    internal fun updateFolderCachesForInbox(email: DisplayEmail) {
        val archiveCurrent = folderCache[R.id.nav_archive]
        if (archiveCurrent != null) {
            folderCache[R.id.nav_archive] = archiveCurrent.filter { it.id != email.id }
        }
        val inboxCurrent = folderCache[R.id.nav_inbox]
        if (inboxCurrent != null && inboxCurrent.none { it.id == email.id }) {
            folderCache[R.id.nav_inbox] = insertSortedByDate(inboxCurrent, email)
        }
    }

    internal fun updateFolderCachesForMove(email: DisplayEmail, targetNavId: Int) {
        // Insert into target cache at top (if already loaded and not already present)
        if (targetNavId == R.id.nav_archive || targetNavId == R.id.nav_trash) {
            val current = folderCache[targetNavId]
            if (current != null && current.none { it.id == email.id }) {
                folderCache[targetNavId] = insertSortedByDate(current, email)
            }
        }
        // Remove from inbox and archive on delete/trash
        if (targetNavId == R.id.nav_trash) {
            val archiveCurrent = folderCache[R.id.nav_archive]
            if (archiveCurrent != null) {
                folderCache[R.id.nav_archive] = archiveCurrent.filter { it.id != email.id }
            }
            val favCurrent = folderCache[R.id.nav_favourite]
            if (favCurrent != null) {
                folderCache[R.id.nav_favourite] = favCurrent.filter { it.id != email.id }
            }
        }
        // Remove from inbox always
        val inboxCurrent = folderCache[R.id.nav_inbox]
        if (inboxCurrent != null) {
            folderCache[R.id.nav_inbox] = inboxCurrent.filter { it.id != email.id }
        }
        // Spam: also remove from archive and favourite
        if (targetNavId != R.id.nav_archive && targetNavId != R.id.nav_trash) {
            val archiveCurrent = folderCache[R.id.nav_archive]
            if (archiveCurrent != null) {
                folderCache[R.id.nav_archive] = archiveCurrent.filter { it.id != email.id }
            }
            val favCurrent = folderCache[R.id.nav_favourite]
            if (favCurrent != null) {
                folderCache[R.id.nav_favourite] = favCurrent.filter { it.id != email.id }
            }
        }
    }

    private val actionModeCallback =
            object : androidx.appcompat.view.ActionMode.Callback {
                override fun onCreateActionMode(
                        mode: androidx.appcompat.view.ActionMode,
                        menu: Menu
                ): Boolean {
                    menu.add(0, 1, 0, getString(R.string.swipe_action_archive))
                            .setIcon(R.drawable.ic_lucide_archive)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(0, 2, 0, getString(R.string.swipe_action_delete))
                            .setIcon(R.drawable.ic_lucide_trash)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(0, 3, 0, "Mark Unread")
                            .setIcon(R.drawable.ic_lucide_eye)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(0, 6, 0, "More")
                            .setIcon(R.drawable.ic_lucide_more_vertical)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    val barTint = getOnAccentColor()
                    for (i in 0 until menu.size()) {
                        menu.getItem(i).icon?.mutate()?.setTint(barTint)
                    }
                    tintActionModeBar()
                    return true
                }

                override fun onPrepareActionMode(
                        mode: androidx.appcompat.view.ActionMode,
                        menu: Menu
                ): Boolean {
                    val allSeen =
                            selectedEmails.isNotEmpty() &&
                                    selectedEmails.all { id ->
                                        emails.find { it.id == id }?.seen == true
                                    }
                    menu.findItem(3)?.title = if (allSeen) "Mark Unread" else "Mark Read"
                    return true
                }

                override fun onActionItemClicked(
                        mode: androidx.appcompat.view.ActionMode,
                        item: MenuItem
                ): Boolean {
                    val account = connectedAccount ?: return false
                    val ids = selectedEmails.toList()

                    if (item.itemId == 6) {
                        showMoreOptionsPopup(mode)
                        return true
                    }

                    mode.finish()
                    val movedEmails = emails.filter { it.id in ids }
                    val accountsById = movedEmails.associate { it.id to (resolveAccountFor(it) ?: account) }
                    when (item.itemId) {
                        1 -> { // Archive
                            // Archiving from Favourites keeps the email flagged, so it
                            // must stay visible there instead of being removed.
                            val keepVisible = selectedFolder == R.id.nav_favourite
                            if (!keepVisible) {
                                removeEmailsAnimated(ids)
                                saveEmailCache()
                            }

                            lifecycleScope.launch {
                                try {
                                    ids.forEach { id ->
                                        val acc = accountsById[id] ?: return@forEach
                                        val archiveId = resolveOrCreateArchive(acc) ?: return@forEach
                                        jmapClient.setMailbox(acc, id, archiveId)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to archive selection", e)
                                }
                            }
                        }
                        2 -> { // Delete
                            removeEmailsAnimated(ids)
                            saveEmailCache()

                            lifecycleScope.launch {
                                try {
                                    ids.forEach { id ->
                                        val acc = accountsById[id] ?: return@forEach
                                        val trashId = resolveMailboxIdByRole(acc, "trash") ?: return@forEach
                                        jmapClient.setMailbox(acc, id, trashId)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to delete selection", e)
                                }
                            }
                        }
                        3 -> { // Toggle Read/Unread
                            val allSeen = ids.all { id -> emails.find { e -> e.id == id }?.seen == true }
                            val newState = !allSeen
                            emails.forEach { if (it.id in ids) it.seen = newState }
                            emailAdapter.notifyDataSetChanged()
                            saveEmailCache()

                            lifecycleScope.launch {
                                try {
                                    ids.forEach { id ->
                                        val acc = resolveAccountForId(id) ?: return@forEach
                                        jmapClient.setSeen(acc, id, newState)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to toggle seen state for selection", e)
                                }
                            }
                        }
                    }
                    return true
                }

                override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
                    selectedEmails.clear()
                    emailAdapter.notifyDataSetChanged()
                }
            }

    internal suspend fun resolveMailboxIdByRole(
            account: JMapClient.ConnectedAccount,
            role: String
    ): String? {
        val fromQuery = jmapClient.resolveMailboxIdByRole(account, role)
        if (fromQuery != null) return fromQuery
        // Some servers don't assign the IMAP special-use role, so the query returns
        // nothing. Fall back to fetching all mailboxes and matching by role, then by name.
        return try {
            val mailboxes = jmapClient.fetchMailboxes(account)
            mailboxes.firstOrNull { it.role?.lowercase() == role.lowercase() }?.id
                ?: mailboxes.firstOrNull { mbox ->
                    mailboxNameMatchesRole(mbox.name, role)
                }?.id
                ?: run {
                    Log.w(TAG, "resolveMailboxIdByRole: no '$role' mailbox for ${account.email}; " +
                            "available=${mailboxes.joinToString { "${it.name}/${it.role}" }}")
                    null
                }
        } catch (e: Exception) {
            Log.w(TAG, "resolveMailboxIdByRole fallback failed for $role", e)
            null
        }
    }

    /**
     * Resolves the archive mailbox for an account, creating it if the server doesn't ship
     * one. Stalwart (and some other servers) don't provision an Archive folder by default
     * but recognise the "archive" special-use role, so we create it on first use.
     */
    internal suspend fun resolveOrCreateArchive(account: JMapClient.ConnectedAccount): String? {
        resolveMailboxIdByRole(account, "archive")?.let { return it }
        val created = jmapClient.createMailbox(account, "Archive", "archive")
        if (created != null) {
            Log.i(TAG, "Created Archive mailbox for ${account.email}")
            // Invalidate cached mailbox list so the new folder shows up in pickers.
            mailboxCache = null
        } else {
            Log.w(TAG, "Could not resolve or create Archive mailbox for ${account.email}")
        }
        return created
    }

    /** Heuristic name match for servers that don't expose IMAP special-use roles. */
    private fun mailboxNameMatchesRole(name: String, role: String): Boolean {
        val n = name.trim().lowercase()
        val candidates = when (role.lowercase()) {
            "archive" -> listOf("archive", "archived", "all mail", "archivio")
            "junk", "spam" -> listOf("junk", "spam", "junk e-mail", "junk email", "posta indesiderata")
            "trash" -> listOf("trash", "deleted", "deleted items", "bin", "cestino")
            "sent" -> listOf("sent", "sent items", "sent mail", "posta inviata", "inviata")
            "drafts" -> listOf("drafts", "draft", "bozze")
            "inbox" -> listOf("inbox", "posta in arrivo")
            else -> listOf(role.lowercase())
        }
        return candidates.any { n == it }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {}
    }

    internal fun startPeriodicSync() {
        syncJob?.cancel()
        requestBatteryOptimizationExemption()
        val account = connectedAccount ?: return
        val currentFolderId = selectedFolder
        val role = getFolderRole(selectedFolder)
        val isFav = selectedFolder == R.id.nav_favourite
        val isInbox = selectedFolder == R.id.nav_inbox
        val isUnifiedInbox = selectedFolder == R.id.nav_unified_inbox
        val labelKeyword = labelNavIds[selectedFolder]
        val folderTitle = getCurrentMailboxTitle()

        syncJob =
                lifecycleScope.launch {
                    // Warm the mailbox cache so the "Move to" sheet opens without waiting on the network.
                    if (mailboxCache == null) {
                        runCatching { mailboxCache = jmapClient.fetchMailboxes(account) }
                    }
                    while (true) {
                        try {
                            if (folderCache[currentFolderId] == null) {
                                status.text =
                                        getString(R.string.status_sync_contacting, folderTitle, debugTs())
                            }

                            if (isUnifiedInbox) {
                                val allAccounts = BackgroundEmailSyncReceiver.readAllAccounts(this@MainActivity)
                                val merged = allAccounts.flatMap { acc ->
                                    try {
                                        val base = jmapClient.fetchEmails(acc, limit = emailLimit).map { e ->
                                            DisplayEmail(
                                                e.id, e.subject, e.from, e.fromEmail,
                                                e.preview, e.fullBody, e.seen, e.isStarred,
                                                e.receivedAt, e.toEmail,
                                                attachments = e.attachments,
                                                accountEmail = acc.email,
                                                labels = e.keywords.toList(),
                                                threadId = e.threadId
                                            )
                                        }
                                        // Chat-style threading: pull replies from other mailboxes
                                        // (e.g. Sent) so a conversation groups under one head, same
                                        // as the single-account inbox does.
                                        val threadIds = base.mapNotNull { it.threadId.ifBlank { null } }.toSet()
                                        val haveIds = base.map { it.id }.toSet()
                                        val extra = try {
                                            jmapClient.fetchThreadMembers(acc, threadIds, haveIds).map {
                                                DisplayEmail(
                                                    it.id, it.subject, it.from, it.fromEmail, it.preview,
                                                    it.fullBody, it.seen, it.isStarred, it.receivedAt, it.toEmail,
                                                    attachments = it.attachments, accountEmail = acc.email,
                                                    labels = it.keywords.toList(), threadId = it.threadId
                                                )
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (_: Exception) { emptyList() }
                                        base + extra
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }.sortedByDescending { it.receivedAt }
                                folderCache[currentFolderId] = merged
                                updateEmailsList(merged)
                                persistOfflineCache(currentFolderId, merged)
                                status.text = if (merged.isEmpty())
                                    getString(R.string.status_sync_ok_empty, folderTitle, debugTs())
                                else getString(R.string.status_sync_ok, merged.size, debugTs(), folderTitle)
                                delay(10000)
                                continue
                            }

                            val mailboxId =
                                    if (role != null) resolveMailboxIdByRole(account, role)
                                    else null
                            val fresh =
                                    if (labelKeyword != null) {
                                        jmapClient.fetchEmailsByKeyword(account, labelKeyword, emailLimit)
                                    } else if (isFav) {
                                        jmapClient.fetchStarredEmails(account, emailLimit)
                                    } else if (isInbox) {
                                        jmapClient.fetchEmails(account, limit = emailLimit)
                                    } else if (role != null && mailboxId == null) {
                                        emptyList()
                                    } else {
                                        jmapClient.fetchEmails(account, mailboxId, emailLimit)
                                    }

                            val newEmailsList =
                                    fresh.map {
                                        DisplayEmail(
                                                it.id,
                                                it.subject,
                                                it.from,
                                                it.fromEmail,
                                                it.preview,
                                                it.fullBody,
                                                it.seen,
                                                it.isStarred,
                                                it.receivedAt,
                                                it.toEmail,
                                                attachments = it.attachments,
                                                accountEmail = account.email,
                                                labels = it.keywords.toList(),
                                                threadId = it.threadId
                                        )
                                    }
                            // Chat-style threading: pull in replies that live in other
                            // mailboxes (e.g. Sent) so a conversation groups under one head.
                            val threadedList = if (isInbox) {
                                val threadIds = newEmailsList.mapNotNull { it.threadId.ifBlank { null } }.toSet()
                                val haveIds = newEmailsList.map { it.id }.toSet()
                                val extra = try {
                                    jmapClient.fetchThreadMembers(account, threadIds, haveIds).map {
                                        DisplayEmail(
                                            it.id, it.subject, it.from, it.fromEmail, it.preview,
                                            it.fullBody, it.seen, it.isStarred, it.receivedAt, it.toEmail,
                                            attachments = it.attachments, accountEmail = account.email,
                                            labels = it.keywords.toList(), threadId = it.threadId
                                        )
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) { emptyList() }
                                (newEmailsList + extra).sortedByDescending { it.receivedAt }
                            } else newEmailsList
                            val mergedList = applyOptimisticFavorite(threadedList, isFav)
                            folderCache[currentFolderId] = mergedList
                            updateEmailsList(mergedList)
                            persistOfflineCache(currentFolderId, mergedList)

                            status.text =
                                    if (fresh.isEmpty())
                                            getString(R.string.status_sync_ok_empty, folderTitle, debugTs())
                                    else getString(R.string.status_sync_ok, fresh.size, debugTs(), folderTitle)
                        } catch (_: CancellationException) {
                            return@launch
                        } catch (e: Throwable) {
                            Log.e(TAG, "Sync failed", e)
                            status.text = getString(R.string.status_sync_failed, e.message ?: "-", debugTs())
                            if (pendingMailboxShow) {
                                pendingMailboxShow = false
                                showMailboxScreen(skipRefresh = true)
                                loadingOverlay.animate().alpha(0f).setDuration(350).withEndAction {
                                    loadingOverlay.visibility = View.GONE
                                    loadingOverlay.alpha = 1f
                                }.start()
                            }
                        }
                        delay(10000)
                    }
                }
    }

    private fun loadUnifiedPushPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        unifiedPushSwitch.isChecked = prefs.getBoolean(KEY_UP_ENABLED, false)
        sseSwitch.isChecked = JmapEventSourceService.isEnabled(this)
    }

    internal fun saveUnifiedPushEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_UP_ENABLED, enabled)
                .apply()
    }

    private fun normalizeUnifiedPushLink(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val withScheme =
                if (trimmed.startsWith("http://", ignoreCase = true) ||
                                trimmed.startsWith("https://", ignoreCase = true)
                ) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
        return try {
            val url = URL(withScheme)
            if (url.protocol != "https" || url.host.isNullOrBlank()) return null
            val topic = url.path.trim('/').ifBlank { getOrCreateUnifiedPushTopic() }
            URL("https", url.host, url.port, "/$topic").toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateUnifiedPushTopic(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_UP_AUTO_TOPIC, null)
        if (!saved.isNullOrBlank()) return saved
        val generated = "jmapjolt-${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_UP_AUTO_TOPIC, generated).apply()
        return generated
    }

    private fun sendUnifiedPushTestNotification() {
        lifecycleScope.launch {
            // Registration is asynchronous: when the switch is toggled on, the fresh
            // endpoint arrives via onNewEndpoint a moment later. Poll the pref for a
            // short window so the test isn't sent to a stale/missing endpoint.
            val endpoint = withContext(Dispatchers.IO) {
                var attempt = 0
                var found: String? = null
                while (attempt < 24) { // ~12s at 500ms
                    found = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getString(KEY_LAST_UP_ENDPOINT, null)
                        ?.takeIf { it.isNotBlank() }
                    if (found != null) break
                    Thread.sleep(500)
                    attempt++
                }
                found
            }
            if (endpoint == null) {
                showThemedSnackbar(getString(R.string.settings_unifiedpush_waiting_endpoint))
                return@launch
            }
            val result =
                    withContext(Dispatchers.IO) {
                        try {
                            val connection = URL(endpoint).openConnection() as HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                            connection.outputStream.use {
                                it.write(getString(R.string.settings_unifiedpush_test_body).toByteArray())
                            }
                            val code = connection.responseCode
                            connection.disconnect()
                            TestNotificationResult(code in 200..299, code)
                        } catch (e: Throwable) {
                            Log.e(TAG, "UnifiedPush test notification failed", e)
                            TestNotificationResult(false, null)
                        }
                    }

            showThemedSnackbar(
                if (result.success) {
                    getString(R.string.settings_unifiedpush_test_sent)
                } else if (result.httpCode != null) {
                    getString(R.string.settings_unifiedpush_error_with_code, result.httpCode)
                } else {
                    getString(R.string.settings_unifiedpush_error)
                }
            )
        }
    }

    internal fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    internal fun persistConnectedAccount(account: JMapClient.ConnectedAccount, serverUrl: String) {
        val existingIndex =
                savedAccounts.indexOfFirst { it.email.equals(account.email, ignoreCase = true) }
        val entry =
                AccountEntry(
                        email = account.email,
                        password = account.password,
                        serverUrl = serverUrl,
                        sessionUrl = account.sessionUrl,
                        apiUrl = account.apiUrl,
                        accountId = account.accountId
                )
        if (existingIndex >= 0) savedAccounts[existingIndex] = entry else savedAccounts.add(entry)
        currentAccountEmail = account.email
        saveAccounts()
        renderAccountHeader()
    }

    private fun loadAccounts() {
        val raw =
                SecureStorage.prefs(this).getString(KEY_ACCOUNTS_JSON, null)
                        ?: return
        val root = JSONObject(raw)
        val list = root.optJSONArray("accounts") ?: JSONArray()
        savedAccounts.clear()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            savedAccounts.add(
                    AccountEntry(
                            email = item.optString("email"),
                            password = item.optString("password"),
                            serverUrl = item.optString("serverUrl"),
                            sessionUrl = item.optString("sessionUrl"),
                            apiUrl = item.optString("apiUrl"),
                            accountId = item.optString("accountId")
                    )
            )
        }
        val current = root.optString("current", "")
        currentAccountEmail = current.ifBlank { null }
        renderAccountHeader()
    }

    internal fun saveAccounts() {
        val accounts = JSONArray()
        savedAccounts.forEach {
            accounts.put(
                    JSONObject()
                            .put("email", it.email)
                            .put("password", it.password)
                            .put("serverUrl", it.serverUrl)
                            .put("sessionUrl", it.sessionUrl)
                            .put("apiUrl", it.apiUrl)
                            .put("accountId", it.accountId)
            )
        }
        val root = JSONObject().put("accounts", accounts).put("current", currentAccountEmail ?: "")
        SecureStorage.prefs(this)
                .edit()
                .putString(KEY_ACCOUNTS_JSON, root.toString())
                .apply()
    }

    private fun closeAccountsList() {
        drawerAccountsList.visibility = View.GONE
        drawerAccountArrow.rotation = 0f
    }

    /** Internal storage file holding the custom avatar photo for an account. */
    private fun accountAvatarFile(email: String): java.io.File =
        java.io.File(filesDir, "avatar_" + email.lowercase().replace(Regex("[^a-z0-9]"), "_") + ".jpg")

    /** Display name shown in the account section; falls back to the local part of the email. */
    internal fun getAccountDisplayName(email: String): String {
        val saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("account_name_$email", null)
        return saved?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
    }

    private fun setAccountDisplayName(email: String, name: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("account_name_$email", name.trim()).apply()
    }

    private fun centerCropSquare(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val dim = minOf(src.width, src.height)
        val x = (src.width - dim) / 2
        val y = (src.height - dim) / 2
        return android.graphics.Bitmap.createBitmap(src, x, y, dim, dim)
    }

    /** Circular avatar: custom photo if present, otherwise a colored disc with the first initial. */
    private fun buildAccountAvatar(email: String, sizePx: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = sizePx / 2f
        val file = accountAvatarFile(email)
        val photo = if (file.exists())
            try { android.graphics.BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null }
        else null
        if (photo != null) {
            val scaled = android.graphics.Bitmap.createScaledBitmap(centerCropSquare(photo), sizePx, sizePx, true)
            paint.shader = android.graphics.BitmapShader(
                scaled,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(r, r, r, paint)
        } else {
            paint.color = getAccountColor(email)
            canvas.drawCircle(r, r, r, paint)
            val letter = getAccountDisplayName(email).firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sizePx * 0.42f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val baseline = r - (tp.descent() + tp.ascent()) / 2f
            canvas.drawText(letter, r, baseline, tp)
        }
        return bmp
    }

    internal fun renderAccountHeader() {
        val current = currentAccountEmail ?: savedAccounts.firstOrNull()?.email.orEmpty()
        val dp = resources.displayMetrics.density

        val textInt = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val secondaryTextInt =
                if (currentTheme == "light") "#5A5A5A".toColorInt() else "#BDBDBD".toColorInt()
        val accentInt = currentAccentColor.toColorInt()
        val logoutRed = "#E53935".toColorInt()
        val addGreen = "#43A047".toColorInt()

        // Keep the header background in sync with the active theme (was hardcoded dark).
        val headerBg = when (currentTheme) {
            "light"  -> "#F6F6F8".toColorInt()
            "oled"   -> "#000000".toColorInt()
            "violet" -> "#160E24".toColorInt()
            else     -> "#212126".toColorInt()
        }
        (drawerAccountRow.parent as? View)?.setBackgroundColor(headerBg)

        // Header: avatar + display name (bold, primary) + email (secondary).
        drawerAccountName.setCompoundDrawablesRelative(null, null, null, null)
        drawerAccountName.text = if (current.isBlank()) "" else getAccountDisplayName(current)
        drawerAccountName.setTextColor(textInt)
        drawerAccountEmail.text = current
        drawerAccountEmail.setTextColor(secondaryTextInt)
        drawerAccountEmail.visibility = if (current.isBlank()) View.GONE else View.VISIBLE
        if (current.isNotBlank()) {
            drawerAccountAvatar.setImageBitmap(buildAccountAvatar(current, (44 * dp).toInt()))
            drawerAccountAvatar.visibility = View.VISIBLE
        } else {
            drawerAccountAvatar.visibility = View.GONE
        }
        drawerAccountArrow.imageTintList = ColorStateList.valueOf(accentInt)

        drawerAccountsList.removeAllViews()

        // Per-account row background: a darkened shade of the active theme so the rows
        // (with pencil/exit icons) match the theme instead of a generic dark grey
        // that clashes under e.g. the iris/violet theme.
        val rowBg = when (currentTheme) {
            "light"  -> "#F0F0F0".toColorInt()
            "oled"   -> "#181818".toColorInt()
            "violet" -> "#0F0918".toColorInt()
            else     -> "#2A2A2A".toColorInt()
        }

        // All accounts, including the currently logged-in one (it shows a red sign-out icon).
        savedAccounts.forEach { account ->
            val isCurrent = account.email.equals(current, ignoreCase = true)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dp).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10 * dp
                    setColor(rowBg)
                    if (isCurrent) setStroke((1.5f * dp).toInt(), accentInt)
                }
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            }

            // Circular avatar for the account.
            row.addView(ImageView(this).apply {
                val sz = (32 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (10 * dp).toInt() }
                setImageBitmap(buildAccountAvatar(account.email, sz))
            })

            // Name (bold) over email (secondary).
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = getAccountDisplayName(account.email)
                    textSize = 14f
                    setTextColor(textInt)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@MainActivity).apply {
                    text = account.email
                    textSize = 12f
                    setTextColor(secondaryTextInt)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (!isCurrent) setOnClickListener {
                    showThemedConfirmDialog(
                        title = "Switch Account",
                        message = "Switch to ${account.email}?",
                        confirmLabel = "Switch"
                    ) {
                        switchToSavedAccount(account)
                        closeAccountsList()
                        drawerLayout.closeDrawer(GravityCompat.START)
                    }
                }
            })

            // Pencil: edit this account's profile (display name + photo).
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_lucide_pencil)
                imageTintList = ColorStateList.valueOf(accentInt)
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                setOnClickListener { showEditProfileDialog(account.email) }
            })

            // Red sign-out / remove icon.
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_lucide_log_out)
                imageTintList = ColorStateList.valueOf(logoutRed)
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                setOnClickListener {
                    showThemedConfirmDialog(
                        title = if (isCurrent) "Sign Out" else "Remove Account",
                        message = if (isCurrent) "Sign out of ${account.email}?" else "Remove ${account.email}?",
                        confirmLabel = if (isCurrent) "Sign Out" else "Remove",
                        isDangerous = true
                    ) { deleteAccount(account) }
                }
            })

            drawerAccountsList.addView(row)
        }

        // Compact green "+" button to add the account you are currently logged into elsewhere.
        drawerAccountsList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_plus)
                imageTintList = ColorStateList.valueOf(addGreen)
                val sz = (40 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setPadding((9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(android.graphics.Color.argb(40, Color.red(addGreen), Color.green(addGreen), Color.blue(addGreen)))
                }
                contentDescription = getString(R.string.drawer_add_account_action)
                setOnClickListener {
                    showAddAccountDialog()
                    closeAccountsList()
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            })
        })
    }

    /** Discord-style crop/rotate editor before the chosen photo becomes the avatar. */
    private fun showAvatarCropDialog(uri: android.net.Uri, email: String) {
        val dp = resources.displayMetrics.density
        val source = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeStream(input, null, opts)
                val maxDim = 1600
                var sample = 1
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                contentResolver.openInputStream(uri)?.use { s2 ->
                    android.graphics.BitmapFactory.decodeStream(s2, null, opts)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Avatar decode failed", e)
            null
        }
        if (source == null) {
            android.widget.Toast.makeText(this, "Could not load image", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBg = getDialogBackgroundColor()
        val accentInt = currentAccentColor.toColorInt()
        val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()

        val cropView = AvatarCropView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (280 * dp).toInt()
            )
            setBitmap(source)
        }

        // Center magnet: snap to 0° when the thumb is within this many units of center.
        val snapThreshold = 8
        val rotateSlider = android.widget.SeekBar(this).apply {
            max = 360
            progress = 180
            progressTintList = ColorStateList.valueOf(accentInt)
            thumbTintList = ColorStateList.valueOf(accentInt)
            progressBackgroundTintList = ColorStateList.valueOf(
                if (currentTheme == "light") "#C0C0C4".toColorInt() else "#5A5A5A".toColorInt()
            )
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && kotlin.math.abs(value - 180) <= snapThreshold && value != 180) {
                        progress = 180
                        return
                    }
                    cropView.rotationDeg = (value - 180).toFloat()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }

        // Rotate icon (left) + slider with a centered "|" marker showing the image's centre.
        val rotateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt() }
            addView(ImageView(this@MainActivity).apply {
                val sz = (24 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (8 * dp).toInt() }
                setImageResource(R.drawable.ic_rotate_cw)
                imageTintList = ColorStateList.valueOf(accentInt)
            })
            addView(android.widget.FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                // Centre marker first so it sits UNDER the slider thumb; exact same colour
                // as the slider track background (not lighter), so it reads as part of the bar.
                addView(View(this@MainActivity).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        (2 * dp).toInt(), (14 * dp).toInt(), Gravity.CENTER
                    )
                    setBackgroundColor(
                        if (currentTheme == "light") "#C0C0C4".toColorInt() else "#5A5A5A".toColorInt()
                    )
                })
                addView(rotateSlider)
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(dialogBg)
            }
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            addView(cropView)
            addView(rotateBar)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
        }
        root.addView(btnRow)

        val dialog = AlertDialog.Builder(this).setView(root).create()

        btnRow.addView(TextView(this).apply {
            text = getString(R.string.action_cancel)
            textSize = 14f
            setTextColor(secondaryColor)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = getString(R.string.action_save)
            textSize = 14f
            setTextColor(accentInt)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener {
                val cropped = cropView.getCroppedBitmap(512)
                if (cropped != null) {
                    try {
                        accountAvatarFile(email).outputStream().use { out ->
                            cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        renderAccountHeader()
                        editProfileAvatarRefresh?.invoke()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Avatar save failed", e)
                    }
                }
                dialog.dismiss()
            }
        })

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            dialog.window?.attributes = lp
        }
    }

    /** Edit dialog: change the display name and the avatar photo for an account. */
    private fun showEditProfileDialog(email: String) {
        val dp = resources.displayMetrics.density
        val dialogBg = getDialogBackgroundColor()
        val accentInt = currentAccentColor.toColorInt()
        val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()
        val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(dialogBg)
            }
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }

        val avatarSz = (96 * dp).toInt()
        val avatarView = ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(avatarSz, avatarSz)
            setImageBitmap(buildAccountAvatar(email, avatarSz))
        }
        // Pencil centered over a slight dark scrim that dims the photo.
        val scrim = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(avatarSz, avatarSz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x55000000)
            }
        }
        val pencil = ImageView(this).apply {
            val sz = (30 * dp).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
            setImageResource(R.drawable.ic_lucide_pencil)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val avatarFrame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSz, avatarSz).apply {
                bottomMargin = (18 * dp).toInt()
            }
            isClickable = true
            isFocusable = true
            addView(avatarView)
            addView(scrim)
            addView(pencil)
        }
        root.addView(avatarFrame)

        // "Change name" label on the left + tappable account-color swatch on the right.
        val colorSwatch = View(this).apply {
            val sz = (22 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getAccountColor(email))
                setStroke((1.5f * dp).toInt(), Color.argb(60, 255, 255, 255))
            }
            isClickable = true
            isFocusable = true
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * dp).toInt() }
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.drawer_change_name)
                textSize = 13f
                setTextColor(accentInt)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(colorSwatch)
        })
        colorSwatch.setOnClickListener {
            showAccountColorDialog(email) {
                (colorSwatch.background as? GradientDrawable)?.setColor(getAccountColor(email))
                editProfileAvatarRefresh?.invoke()
            }
        }

        val nameInput = EditText(this).apply {
            setText(getAccountDisplayName(email))
            hint = getString(R.string.drawer_display_name_hint)
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(hintColor)
            backgroundTintList = ColorStateList.valueOf(hintColor)
            isSingleLine = true
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(13), noArabicFilter())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(nameInput)

        editProfileAvatarRefresh = { avatarView.setImageBitmap(buildAccountAvatar(email, avatarSz)) }
        avatarFrame.setOnClickListener {
            editingAvatarEmail = email
            pickAvatarLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt() }
        }
        root.addView(btnRow)

        val dialog = AlertDialog.Builder(this).setView(root).create()

        btnRow.addView(TextView(this).apply {
            text = getString(R.string.action_cancel)
            textSize = 14f
            setTextColor(secondaryColor)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = getString(R.string.action_save)
            textSize = 14f
            setTextColor(accentInt)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener {
                setAccountDisplayName(email, nameInput.text.toString())
                renderAccountHeader()
                dialog.dismiss()
            }
        })

        dialog.setOnDismissListener {
            editingAvatarEmail = null
            editProfileAvatarRefresh = null
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
            dialog.window?.attributes = lp
        }
    }

    private fun deleteAccount(account: AccountEntry) {
        savedAccounts.removeAll { it.email.equals(account.email, ignoreCase = true) }
        saveAccounts()
        if (account.email == currentAccountEmail) {
            val next = savedAccounts.firstOrNull()
            if (next != null) {
                switchToSavedAccount(next)
            } else {
                currentAccountEmail = null
                connectedAccount = null
                closeAccountsList()
                drawerLayout.closeDrawer(GravityCompat.START)
                showLoginScreen()
            }
        } else {
            // If only one account remains and we're in unified inbox, return to that account's inbox
            if (savedAccounts.size <= 1 && selectedFolder == R.id.nav_unified_inbox) {
                selectedFolder = R.id.nav_inbox
                applyFolderFilterAndRefresh()
            }
            renderAccountHeader()
            navigationView.post { rebuildDrawerMenu() }
        }
    }

    internal fun getAccountColor(email: String): Int {
        val saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("account_color_$email", Int.MIN_VALUE)
        if (saved != Int.MIN_VALUE) return saved
        val hue = kotlin.math.abs(email.hashCode() % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))
    }

    internal fun resolveAccountFor(email: DisplayEmail): JMapClient.ConnectedAccount? {
        if (email.accountEmail.isBlank()) return connectedAccount
        val entry = savedAccounts.firstOrNull { it.email.equals(email.accountEmail, ignoreCase = true) }
            ?: return connectedAccount
        return JMapClient.ConnectedAccount(
            email = entry.email,
            password = entry.password,
            sessionUrl = entry.sessionUrl,
            apiUrl = entry.apiUrl,
            accountId = entry.accountId
        )
    }

    internal fun resolveAccountForId(emailId: String): JMapClient.ConnectedAccount? {
        val email = baseEmails.find { it.id == emailId } ?: return connectedAccount
        return resolveAccountFor(email)
    }

    private fun setAccountColor(email: String, color: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt("account_color_$email", color).apply()
    }

    internal fun switchToSavedAccount(account: AccountEntry, forceInbox: Boolean = false) {
        connectedAccount = JMapClient.ConnectedAccount(
            email = account.email,
            password = account.password,
            sessionUrl = account.sessionUrl,
            apiUrl = account.apiUrl,
            accountId = account.accountId
        )
        currentAccountEmail = account.email
        loadLabels()
        saveAccounts()
        renderAccountHeader()

        // Show UI immediately, load cache async, then start live sync
        showMailboxScreen(skipRefresh = true)
        status.text = getString(R.string.status_fetch_new, debugTs())
        if (JmapEventSourceService.isEnabled(this)) {
            JmapEventSourceService.stop(this)
            JmapEventSourceService.start(this)
        }

        lifecycleScope.launch {
            val cached = emailCache.load(account.email)
            if (cached != null) {
                selectedFolder = if (forceInbox) R.id.nav_inbox else cached.selectedFolder
                folderCache.clear()
                folderCache.putAll(cached.folderCache)
                updateEmailsList(cached.emails)
                updateTopBarState()
                rebuildDrawerMenu()
            }
            startPeriodicSync()
            fetchAllFoldersBackground()
        }
    }

    private fun restoreLastAccountSession(): Boolean {
        val target =
                currentAccountEmail?.let { selected ->
                    savedAccounts.firstOrNull { it.email == selected }
                }
                        ?: savedAccounts.firstOrNull()
        if (target == null) return false
        switchToSavedAccount(target, forceInbox = true)
        triggerSyncOnAppUpdateIfNeeded()
        return true
    }

    private fun triggerSyncOnAppUpdateIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentVersion =
                try {
                    val pi = packageManager.getPackageInfo(packageName, 0)
                    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)
                } catch (_: PackageManager.NameNotFoundException) {
                    -1L
                }
        val lastVersion = prefs.getLong(KEY_LAST_SYNC_APP_VERSION, -1L)

        if (currentVersion > 0 && currentVersion != lastVersion) {
            refreshInboxNow()
            prefs.edit().putLong(KEY_LAST_SYNC_APP_VERSION, currentVersion).apply()
        }
    }

    internal fun getCategoryDisplayName(id: Int): String =
        categoryNames[id]?.takeIf { it.isNotBlank() } ?: getDefaultCategoryTitle(id)

    internal fun getDefaultCategoryTitle(id: Int): String {
        return when (id) {
            R.id.nav_unified_inbox -> "Unified Inbox"
            R.id.nav_inbox -> "Inbox"
            R.id.nav_favourite -> "Favorite"
            R.id.nav_archive -> "Archive"
            R.id.nav_sent -> "Sent"
            R.id.nav_drafts -> "Drafts"
            R.id.nav_spam -> "Spam"
            R.id.nav_trash -> "Trash"
            else -> "Folder"
        }
    }

    internal fun getCategoryIcon(id: Int): Int {
        return when (id) {
            R.id.nav_unified_inbox -> R.drawable.ic_lucide_inbox
            R.id.nav_inbox -> R.drawable.ic_lucide_inbox
            R.id.nav_favourite -> R.drawable.ic_lucide_star
            R.id.nav_archive -> R.drawable.ic_lucide_archive
            R.id.nav_sent -> R.drawable.ic_lucide_send
            R.id.nav_drafts -> R.drawable.ic_lucide_file_text
            R.id.nav_spam -> R.drawable.ic_lucide_ban
            R.id.nav_trash -> R.drawable.ic_lucide_trash
            else -> R.drawable.ic_lucide_inbox
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Only intercept home/up when drawer indicator is NOT showing (i.e. we are in a sub-view)
        if (item.itemId == android.R.id.home && !drawerToggle.isDrawerIndicatorEnabled) {
            if (settingsContainer.visibility == View.VISIBLE) {
                if (currentSettingsSection != SettingsSection.ROOT) {
                    attemptLeaveSettingsSubmenu()
                } else {
                    showMailboxScreen()
                }
                return true
            }
            if (isShowingEmailDetail) {
                closeEmailDetail()
                return true
            }
        }

        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        syncJob?.cancel()
        unregisterReceiver(pushMessageReceiver)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (settingsContainer.visibility == View.VISIBLE &&
                        currentSettingsSection != SettingsSection.ROOT
        ) {
            attemptLeaveSettingsSubmenu()
            return true
        } else if (settingsContainer.visibility == View.VISIBLE &&
                        currentSettingsSection == SettingsSection.ROOT
        ) {
            showMailboxScreen()
            return true
        } else if (isShowingEmailDetail) {
            closeEmailDetail()
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMailtoIntent(intent)
        handleWidgetIntent(intent)
        handleCalendarIntent(intent)
        applyPendingCalendarIntent()
    }

    private fun handleCalendarIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CALENDAR, false) == true) {
            intent.removeExtra(EXTRA_OPEN_CALENDAR)
            pendingOpenCalendar = true
            pendingCalendarNewEvent = intent.getBooleanExtra(EXTRA_NEW_EVENT, false)
            intent.removeExtra(EXTRA_NEW_EVENT)
            pendingCalendarEventStart = intent.getLongExtra(EXTRA_OPEN_EVENT_START, 0L)
            intent.removeExtra(EXTRA_OPEN_EVENT_START)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_DRAWER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_DRAWER)
            drawerLayout.post { drawerLayout.openDrawer(GravityCompat.START) }
        }
    }

    /**
     * Apply a pending calendar-widget request. Called after the mailbox/session UI is ready
     * (end of onCreate, or onNewIntent) so the calendar screen is not overwritten by the
     * session restore that runs after the intent is parsed.
     */
    private fun applyPendingCalendarIntent() {
        if (!pendingOpenCalendar) return
        pendingOpenCalendar = false
        val newEvent = pendingCalendarNewEvent
        val eventStart = pendingCalendarEventStart
        pendingCalendarNewEvent = false
        pendingCalendarEventStart = 0L
        showCalendarScreen()
        calendarPanelView?.post {
            val panel = calendarPanelView ?: return@post
            when {
                newEvent -> { panel.goToWeekOf(System.currentTimeMillis()); panel.startNewEvent() }
                eventStart > 0L -> panel.goToWeekOf(eventStart)
                else -> panel.goToWeekOf(System.currentTimeMillis())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onboardingPermRefresh?.invoke()
    }

    override fun onPause() {
        super.onPause()
        saveEmailCache()
    }

    override fun onStop() {
        super.onStop()
        saveEmailCache()
    }

    internal fun getCurrentMailboxTitle(): String {
        labelNavIds[selectedFolder]?.let { kw ->
            labelByKeyword(kw)?.let { return it.name }
        }
        return categoryNames[selectedFolder]?.takeIf { it.isNotBlank() }
                ?: getDefaultCategoryTitle(selectedFolder)
    }

    private class SimpleSelectionListener(private val onSelected: () -> Unit) :
            AdapterView.OnItemSelectedListener {
        private var firstEvent = true

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (firstEvent) {
                firstEvent = false
                return
            }
            onSelected()
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        internal const val TAG = "MainActivity"
        // Chat-thread expansion reveals messages this many at a time.
        internal const val THREAD_PAGE = 5
        internal const val PREFS_NAME = "mail_prefs"
        internal const val EXTRA_OPEN_CALENDAR = "open_calendar"
        internal const val EXTRA_NEW_EVENT = "open_calendar_new_event"
        internal const val EXTRA_OPEN_EVENT_START = "open_calendar_event_start"
        internal const val EXTRA_OPEN_DRAWER = "open_drawer"

        // Pull-to-refresh trigger distance (default is ~64dp; raised to avoid
        // accidental refreshes while swiping the top email row horizontally).
        internal const val PULL_TO_REFRESH_TRIGGER_DP = 160
        // Trigger the next page when within this many rows of the bottom.
        internal const val LOAD_MORE_THRESHOLD = 10

        // Detects common HTML elements so HTML fragments (no <html> root) are rendered as
        // markup instead of being escaped and shown as raw text.
        internal val HTML_MARKUP_REGEX = Regex(
            "</?(?:p|div|br|span|a|table|tr|td|th|tbody|thead|ul|ol|li|blockquote|" +
                "h[1-6]|strong|em|b|i|u|img|pre|code|font|hr|center|dl|dt|dd|figure|" +
                "article|section|head|body|html)\\b",
            RegexOption.IGNORE_CASE
        )

        private const val KEY_WELCOME_SHOWN = "welcome_shown"
        internal const val KEY_CATEGORY_ORDER = "category_order"
        private const val KEY_UP_ENABLED = "up_enabled"
        private const val KEY_UP_MANUAL_DISTRIBUTOR = "up_manual_distributor"
        private const val KEY_UP_AUTO_TOPIC = "up_auto_topic"
        private const val KEY_LAST_UP_ENDPOINT = "last_up_endpoint"
        internal const val KEY_SWIPE_RIGHT_ACTION = "swipe_right_action"
        internal const val KEY_SWIPE_LEFT_ACTION = "swipe_left_action"
        private const val KEY_ACCOUNTS_JSON = "accounts_json"
        private const val KEY_LAST_SYNC_APP_VERSION = "last_sync_app_version"
        internal const val KEY_ACCENT_COLOR = "accent_color"
        internal const val KEY_LABELS_JSON = "labels_json"
        // Refined accents: same hue families as before, shifted to brighter,
        // slightly desaturated tones that read well on dark and light surfaces.
        val ACCENT_COLORS = listOf(
            "#3D8BFD", "#3FA65C", "#9C5BD1",
            "#E8593C", "#0FA3B1", "#D84A7F", "#F2A33C"
        )

        // Old palette -> refined palette, for migrating saved preferences.
        val LEGACY_ACCENT_MAP = mapOf(
            "#1976D2" to "#3D8BFD", "#2E7D32" to "#3FA65C", "#7B1FA2" to "#9C5BD1",
            "#D84315" to "#E8593C", "#00838F" to "#0FA3B1", "#AD1457" to "#D84A7F",
            "#F57F17" to "#F2A33C"
        )
    }

    internal fun showThemedConfirmDialog(
        title: String,
        message: String,
        confirmLabel: String,
        isDangerous: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val dialogBg = getDialogBackgroundColor()
        val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()
        val confirmColor = if (isDangerous) "#EF5350".toColorInt() else currentAccentColor.toColorInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * dp).toInt()
            setPadding(p, p, p, (14 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(dialogBg)
            }
        }

        if (title.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
            })
        }

        root.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(secondaryColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (20 * dp).toInt() }
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(btnRow)

        val dialog = AlertDialog.Builder(this).setView(root).create()

        btnRow.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(secondaryColor)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = confirmLabel
            textSize = 14f
            setTextColor(confirmColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); onConfirm() }
        })

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            dialog.window?.attributes = lp
        }
    }

    internal fun showLinkConfirmationDialog(url: String) {
        val dp = resources.displayMetrics.density
        val dialogBg = getDialogBackgroundColor()
        val accentInt = currentAccentColor.toColorInt()
        val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * dp).toInt()
            setPadding(p, p, p, (14 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(dialogBg)
            }
        }

        root.addView(TextView(this).apply {
            text = "Open Link?"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
        })

        root.addView(TextView(this).apply {
            text = url
            textSize = 12f
            setTextColor(secondaryColor)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (20 * dp).toInt() }
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(btnRow)

        val dialog = AlertDialog.Builder(this).setView(root).create()

        btnRow.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(secondaryColor)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Open"
            textSize = 14f
            setTextColor(accentInt)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                try {
                    val uri = Uri.parse(url)
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "https" || scheme == "http" || scheme == "mailto") {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } else {
                        android.widget.Toast.makeText(this@MainActivity, "Cannot open link", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "Cannot open link", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            dialog.window?.attributes = lp
        }
    }

    internal fun showMoveLabelPicker(
        mailboxes: List<JMapClient.MailboxInfo>,
        ids: List<String>,
        mode: androidx.appcompat.view.ActionMode?,
        disabledRoles: Set<String> = emptySet(),
        onPicked: (() -> Unit)? = null
    ) {
        val account = connectedAccount ?: return
        val dp = resources.displayMetrics.density
        val bgColor = getDialogBackgroundColor()
        val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()
        val accentInt = currentAccentColor.toColorInt()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * dp
                setColor(bgColor)
            }
            elevation = 8 * dp
        }

        // Title row
        outer.addView(TextView(this).apply {
            text = "Move to"
            textSize = 13f
            setTextColor(secondaryColor)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        })

        outer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x22FFFFFF)
        })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(mailboxes.size, 6) * (52 * dp).toInt()
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var dialog: AlertDialog? = null

        mailboxes.forEach { mbox ->
            val iconRes = when (mbox.role?.lowercase()) {
                "inbox" -> R.drawable.ic_lucide_inbox
                "archive" -> R.drawable.ic_lucide_archive
                "sent" -> R.drawable.ic_lucide_send
                "junk", "spam" -> R.drawable.ic_lucide_ban
                "starred", "flagged" -> R.drawable.ic_lucide_star
                else -> R.drawable.ic_lucide_tag
            }
            val isDisabled = mbox.role?.lowercase() in disabledRoles
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
                )
                setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
                isClickable = !isDisabled
                isFocusable = !isDisabled
                alpha = if (isDisabled) 0.4f else 1f
                if (!isDisabled) {
                    background = ContextCompat.getDrawable(
                        this@MainActivity,
                        android.util.TypedValue().also {
                            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                        }.resourceId
                    )
                    setOnClickListener {
                        dialog?.dismiss()
                        val mailboxId = mbox.id
                        val mboxRole = mbox.role?.lowercase()
                        // Capture account+role mapping BEFORE removeEmailsAnimated wipes
                        // the lists. The picker shows the active account's mailboxes, so the
                        // cached mailboxId only applies to same-account moves; cross-account
                        // moves must re-resolve the target mailbox by role on the email's own
                        // account (a foreign mailboxId would silently fail on the server).
                        val accByIdForMove = emails.filter { it.id in ids }
                            .associate { it.id to (resolveAccountFor(it) ?: account) }
                        mode?.finish()
                        clearSelection()
                        onPicked?.invoke()
                        removeEmailsAnimated(ids)
                        saveEmailCache()
                        lifecycleScope.launch {
                            try {
                                ids.forEach { id ->
                                    val acc = accByIdForMove[id] ?: account
                                    val sameAccount = acc.email.equals(account.email, ignoreCase = true)
                                    val targetId = when {
                                        mboxRole == "archive" -> resolveOrCreateArchive(acc)
                                        mboxRole != null -> resolveMailboxIdByRole(acc, mboxRole)
                                        sameAccount -> mailboxId
                                        else -> null // custom folder: no cross-account mapping
                                    } ?: return@forEach
                                    jmapClient.setMailbox(acc, id, targetId)
                                    if (mboxRole == "inbox") {
                                        BackgroundEmailSyncReceiver.addToBaseline(this@MainActivity, acc.email, listOf(id))
                                    }
                                }
                            }
                            catch (e: Exception) { Log.e(TAG, "Failed label move", e) }
                        }
                    }
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(accentInt)
                val sz = (20 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (16 * dp).toInt() }
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            row.addView(TextView(this).apply {
                text = mbox.name
                textSize = 15f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            list.addView(row)
        }

        scroll.addView(list)
        outer.addView(scroll)

        dialog = AlertDialog.Builder(this)
            .setView(outer)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showMoreOptionsPopup(mode: androidx.appcompat.view.ActionMode?) {
        val account = connectedAccount ?: return
        val dp = resources.displayMetrics.density
        val ids = selectedEmails.toList()
        val allFavorites = ids.isNotEmpty() && ids.all { id -> emails.find { it.id == id }?.isFavorite == true }
        val darker = darkenColor(currentAccentColor.toColorInt())

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8 * dp
                setColor(darker)
            }
            val vp = (4 * dp).toInt()
            setPadding(0, vp, 0, vp)
            elevation = 8 * dp
        }

        var popupRef: android.widget.PopupWindow? = null

        fun row(label: String, iconRes: Int, action: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (200 * dp).toInt(), (48 * dp).toInt()
                )
                val hp = (16 * dp).toInt()
                setPadding(hp, 0, hp, 0)
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    val sz = (18 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (12 * dp).toInt() }
                })
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 14f; setTextColor(Color.WHITE)
                })
                setOnClickListener { popupRef?.dismiss(); action() }
            }

        // Sent appears as a move target only outside the Sent folder and only when the
        // selection involves mail this account actually sent. It is greyed out when the
        // selection mixes sent and received mail (received mail can't be filed as Sent).
        val isSentBy = { id: String ->
            val em = emails.find { it.id == id }
            val emAccount = if (em != null) resolveAccountFor(em) else null
            em?.fromEmail.equals((emAccount ?: account).email, ignoreCase = true)
        }
        val anySent = ids.any(isSentBy)
        val allSent = ids.all(isSentBy)
        val includeSent = selectedFolder != R.id.nav_sent && selectedFolder != R.id.nav_unified_inbox && anySent
        container.addView(row("Move to", R.drawable.ic_lucide_folder_input) {
            val excludedRoles = buildList {
                add("drafts")
                add("trash")
                if (!includeSent) add("sent")
                // Already in (unified) inbox: "Move to Inbox" is a no-op, hide it.
                if (selectedFolder == R.id.nav_inbox || selectedFolder == R.id.nav_unified_inbox) add("inbox")
            }
            val disabledRoles = if (allSent) emptySet() else setOf("sent")
            fun present(mailboxes: List<JMapClient.MailboxInfo>) {
                val filtered = mailboxes.filter { it.role?.lowercase() !in excludedRoles }
                if (filtered.isNotEmpty()) showMoveLabelPicker(filtered, ids, mode, disabledRoles)
            }
            val resolvedAccount = ids.firstOrNull()?.let { resolveAccountForId(it) } ?: account
            val cached = mailboxCache
            if (cached != null) {
                // Instant: show from cache, refresh in the background for next time.
                present(cached)
                lifecycleScope.launch {
                    runCatching { jmapClient.fetchMailboxes(resolvedAccount) }.getOrNull()?.let { mailboxCache = it }
                }
            } else {
                lifecycleScope.launch {
                    val mailboxes = jmapClient.fetchMailboxes(resolvedAccount)
                    mailboxCache = mailboxes
                    present(mailboxes)
                }
            }
        })

        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x22FFFFFF)
        })

        container.addView(row("Label", R.drawable.ic_lucide_tag) {
            mode?.finish()
            clearSelection()
            showLabelPicker(ids)
        })

        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x22FFFFFF)
        })

        container.addView(row(
            if (allFavorites) "Remove from Favorites" else "Add to Favorites",
            R.drawable.ic_lucide_star
        ) {
            val newState = !allFavorites
            // Mirror the star-button path so the Favourites view updates instantly:
            // flag the rows, record the optimistic override, and patch the cache.
            ids.forEach { id ->
                emails.find { it.id == id }?.isFavorite = newState
                baseEmails.find { it.id == id }?.isFavorite = newState
                optimisticFavorite[id] = newState
                val source = emails.find { it.id == id } ?: baseEmails.find { it.id == id }
                if (source != null) updateFolderCachesForFavorite(source.copy(), newState)
            }
            mode?.finish()
            clearSelection()
            // Removing a favourite while viewing Favourites drops it from the list now.
            if (!newState && selectedFolder == R.id.nav_favourite) {
                emails.removeAll { it.id in ids }
                baseEmails.removeAll { it.id in ids }
                folderCache[R.id.nav_favourite] = emails.toList()
            }
            emailAdapter.notifyDataSetChanged()
            emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
            emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
            saveEmailCache()
            lifecycleScope.launch {
                ids.forEach { id ->
                    val acc = resolveAccountForId(id) ?: account
                    try { jmapClient.setFavorite(acc, id, newState) }
                    catch (e: Exception) { Log.e(TAG, "Failed favorite toggle", e) }
                }
            }
        })

        val pw = android.widget.PopupWindow(
            container,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).also {
            it.elevation = 10 * dp
            it.isOutsideTouchable = true
        }
        popupRef = pw
        pw.showAsDropDown(toolbar, toolbar.width - (220 * dp).toInt(), 0)
    }

    private fun forceShowMenuIcons(menu: Menu) {
        try {
            val m = menu.javaClass.getDeclaredMethod("setOptionalIconsVisible", Boolean::class.java)
            m.isAccessible = true
            m.invoke(menu, true)
        } catch (_: Exception) {}
    }

    internal fun fetchAllFoldersBackground() {
        val account = connectedAccount ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (navId in categoryOrder) {
                    val role = getFolderRole(navId)
                    val isFav = navId == R.id.nav_favourite
                    val isInbox = navId == R.id.nav_inbox

                    val fresh = if (isFav) {
                        jmapClient.fetchStarredEmails(account)
                    } else if (isInbox) {
                        jmapClient.fetchEmails(account)
                    } else if (role != null) {
                        val mailboxId = jmapClient.resolveMailboxIdByRole(account, role)
                        if (mailboxId != null) jmapClient.fetchEmails(account, mailboxId) else continue
                    } else {
                        continue
                    }

                    val newEmailsList = fresh.map {
                        DisplayEmail(it.id, it.subject, it.from, it.fromEmail, it.preview, it.fullBody, it.seen, it.isStarred, it.receivedAt, attachments = it.attachments, labels = it.keywords.toList())
                    }
                    folderCache[navId] = newEmailsList

                    if (navId == selectedFolder) {
                        withContext(Dispatchers.Main) {
                            updateEmailsList(newEmailsList)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    saveEmailCache()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background fetch all folders failed", e)
            }
        }
    }

    internal fun saveEmailCache() {
        val email = currentAccountEmail ?: return
        if (folderCache.isEmpty() && emails.isEmpty()) return
        val snapshot = HashMap(folderCache)
        val currentList = emails.toList()
        val folder = selectedFolder
        lifecycleScope.launch {
            emailCache.save(email, folder, snapshot, currentList)
            InboxWidgetProvider.refreshAll(applicationContext)
        }
    }
}

/** InputFilter that rejects Arabic-script characters (used for account names and labels). */
internal fun noArabicFilter(): android.text.InputFilter = android.text.InputFilter { source, start, end, _, _, _ ->
    val arabic = source.subSequence(start, end).any { ch ->
        ch in '؀'..'ۿ' || ch in 'ݐ'..'ݿ' || ch in 'ࢠ'..'ࣿ' ||
            ch in 'ﭐ'..'﷿' || ch in 'ﹰ'..'﻿'
    }
    if (arabic) "" else null
}
