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
import android.provider.OpenableColumns
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

    private enum class SwipeAction {
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
    private var pendingMailboxShow = false
    internal lateinit var loginContainer: LinearLayout
    internal lateinit var loginBackBtn: ImageView
    private lateinit var loadingOverlay: FrameLayout
    internal lateinit var settingsContainer: ScrollView
    private lateinit var settingsMenuContainer: LinearLayout
    private lateinit var settingsGeneralContainer: LinearLayout
    private lateinit var settingsGeneralHeader: LinearLayout
    private lateinit var settingsGeneralContent: LinearLayout
    internal lateinit var settingsGeneralChevron: ImageView
    private lateinit var settingsLabelsContainer: LinearLayout
    private lateinit var settingsLabelsHeader: LinearLayout
    private lateinit var settingsLabelsContent: LinearLayout
    internal lateinit var settingsLabelsChevron: ImageView
    private lateinit var settingsSwipeContainer: LinearLayout
    private lateinit var settingsUnifiedPushContainer: LinearLayout
    private lateinit var settingsUnifiedPushHeader: LinearLayout
    private lateinit var settingsUnifiedPushContent: LinearLayout
    internal lateinit var settingsUnifiedPushChevron: ImageView

    private lateinit var settingsThemeContainer: LinearLayout
    private lateinit var settingsThemeHeader: LinearLayout
    private lateinit var settingsThemeContent: LinearLayout
    internal lateinit var settingsThemeChevron: ImageView
    internal lateinit var settingsCalendarChevron: ImageView
    internal lateinit var settingsImportIcsRow: TextView
    internal lateinit var settingsExportIcsRow: TextView
    private lateinit var settingsInfoRow: LinearLayout
    internal lateinit var settingsInfoIcon: ImageView
    internal lateinit var settingsInfoArrow: ImageView
    internal lateinit var loadImagesSwitch: SwitchCompat
    internal lateinit var loadFaviconsSwitch: SwitchCompat
    internal var themeIdx: Int = 0
    internal lateinit var themeDropdown: LinearLayout
    private lateinit var themeDropdownText: TextView
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
    private lateinit var searchChipsScroll: android.widget.HorizontalScrollView
    private lateinit var searchChipsRow: LinearLayout
    private var searchScope: Int? = null
    internal lateinit var detailBody: LinearLayout
    private lateinit var detailScroll: androidx.core.widget.NestedScrollView
    private var detailBarHidden = false
    private var detailBarHeight = 0
    private var detailBarLastToggleMs = 0L
    private var detailSwipeAnimating = false
    private val prefetchingIds = mutableSetOf<String>()
    private lateinit var detailWebView: android.webkit.WebView
    // Preview panel that slides in with the finger during detail swipes,
    // showing the adjacent email's content instead of an empty gap.
    private var detailPreviewPanel: LinearLayout? = null
    private var detailPreviewWebView: android.webkit.WebView? = null
    private var detailPreviewKey: String? = null
    private lateinit var mailSwipeRefresh: SwipeRefreshLayout
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
    private var swipeRightActionIdx: Int = 0
    private var swipeLeftActionIdx: Int = 0
    internal lateinit var swipeRightDropdown: LinearLayout
    internal lateinit var swipeLeftDropdown: LinearLayout
    private lateinit var swipeRightDropdownText: TextView
    private lateinit var swipeLeftDropdownText: TextView
    internal lateinit var settingsCalProviderDropdown: LinearLayout
    private lateinit var settingsCalProviderText: TextView
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
    private lateinit var drawerAccountName: TextView
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
    private lateinit var accentColorRow: LinearLayout
    internal var currentAccentColor: String = "#3D8BFD"

    /** User labels (ordered) + drawer menu ids assigned to each label keyword. */
    internal val labels = mutableListOf<EmailLabel>()
    internal val accountLabelsCache = mutableMapOf<String, List<EmailLabel>>()
    internal val labelNavIds = linkedMapOf<Int, String>()
    internal lateinit var detailLabelRowView: LinearLayout
    private var labelDragHelper: ItemTouchHelper? = null

    private val categoryOrder =
            mutableListOf(
                    R.id.nav_inbox,
                    R.id.nav_favourite,
                    R.id.nav_archive,
                    R.id.nav_sent,
                    R.id.nav_drafts,
                    R.id.nav_spam,
                    R.id.nav_trash
            )
    private val categoryNames = mutableMapOf<Int, String>()
    internal val emails = mutableListOf<DisplayEmail>()
    // Current page size for the visible folder. Grows by PAGE_SIZE on scroll-to-bottom.
    private var emailLimit = JMapClient.DEFAULT_EMAIL_LIMIT
    // True while a "load more" fetch is in flight, to avoid stacking requests.
    private var isLoadingMore = false
    internal lateinit var emailAdapter: EmailAdapter
    internal lateinit var jmapClient: JMapClient
    private lateinit var emailCache: EmailCache
    internal var connectedAccount: JMapClient.ConnectedAccount? = null
    internal val savedAccounts = mutableListOf<AccountEntry>()
    internal var currentAccountEmail: String? = null
    internal var selectedFolder: Int = R.id.nav_inbox
    private var prevUpdateFolder: Int = -1
    internal val folderCache = mutableMapOf<Int, List<DisplayEmail>>()
    private var syncJob: Job? = null
    internal var currentSettingsSection: SettingsSection = SettingsSection.ROOT
    internal var currentTheme: String = "gray"
    internal val selectedEmails = mutableSetOf<String>()
    internal val baseEmails = mutableListOf<DisplayEmail>() // unfiltered list for search
    // Pending request from a widget tap: open this email once its account's data is loaded.
    private var pendingWidgetEmailId: String? = null
    private var pendingWidgetAccount: String? = null
    private var widgetSwitchAttempted = false
    internal var isSearchActive = false
    private var wasImeVisible = false
    private lateinit var selectionBarContainer: LinearLayout
    internal lateinit var selectionCountText: TextView
    internal lateinit var selectionCloseBtn: ImageView
    internal lateinit var selectionArchiveBtn: ImageView
    internal lateinit var selectionDeleteBtn: ImageView
    internal lateinit var selectionReadBtn: ImageView
    internal lateinit var selectionMoreBtn: ImageView
    private lateinit var searchInput: EditText
    private lateinit var searchClearBtn: ImageView
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

    private fun showLoginScreen() {
        onboardingContainer.visibility = View.GONE
        loginContainer.visibility = View.VISIBLE
        val loginBg = when (currentTheme) {
            "light"  -> android.graphics.Color.parseColor("#F6F6F8")
            "oled"   -> android.graphics.Color.BLACK
            "violet" -> android.graphics.Color.parseColor("#160E24")
            else     -> android.graphics.Color.parseColor("#212126")
        }
        loginContainer.setBackgroundColor(loginBg)
        loginBackBtn.visibility = View.VISIBLE
        loginBackBtn.bringToFront()
        loginBackBtn.setOnClickListener { showOnboarding() }
        mailboxContainer.visibility = View.GONE
        settingsContainer.visibility = View.GONE
        status.visibility = View.VISIBLE
        emailDetailContainer.visibility = View.GONE
        fabCompose.visibility = View.GONE
        customTopBar.visibility = View.GONE
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        emailInput.text?.clear()
        passwordInput.text?.clear()
        serverUrlInput.text?.clear()
        updateFormState()
        animateLoginEntrance()
    }

    private fun updateFormState() {
        val email = emailInput.text.toString().trim()
        val isEmailValid = Patterns.EMAIL_ADDRESS.matcher(email).matches() && email.contains("@")

        val hasPassword = passwordInput.text.toString().isNotBlank()
        val hasServerUrl = serverUrlInput.text.toString().isNotBlank()
        loginButton.isEnabled = isEmailValid && hasPassword && hasServerUrl
        loginButton.alpha = if (loginButton.isEnabled) 1f else 0.5f

        status.text = when {
            email.isBlank() -> getString(R.string.status_idle)
            isEmailValid -> getString(R.string.status_initial)
            else -> getString(R.string.status_invalid_email)
        }
    }

    private fun connectAndOpenMailbox() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val server = serverUrlInput.text.toString().trim()
        hideKeyboard()

        loginButton.isEnabled = false
        loginButton.alpha = 0.5f
        status.text = getString(R.string.status_connecting)
        loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result =
                    JMapClient(this@MainActivity)
                            .connect(email = email, password = password, serverInput = server)

            if (result.success && result.connectedAccount != null) {
                connectedAccount = result.connectedAccount
                currentAccountEmail = email
                loadLabels()
                persistConnectedAccount(result.connectedAccount, server)
                renderAccountHeader()
                drawerAccountName.text = email
                status.text =
                        getString(
                                R.string.status_connected_with_endpoint,
                                result.resolvedSessionUrl ?: "-"
                        )
                pendingMailboxShow = true
                refreshInboxNow {
                    fetchAllFoldersBackground()
                }
                if (unifiedPushSwitch.isChecked) {
                    registerUnifiedPushAuto("")
                }
                if (JmapEventSourceService.isEnabled(this@MainActivity)) {
                    JmapEventSourceService.start(this@MainActivity)
                }
            } else {
                loadingOverlay.visibility = View.GONE
                val attempted = result.attemptedEndpoints.joinToString(" | ")
                status.text =
                        getString(
                                R.string.status_connection_failed_verbose,
                                result.errorMessage ?: getString(R.string.status_connection_failed),
                                attempted
                        )
                updateFormState()
            }
        }
    }

    internal var isShowingEmailDetail = false

    /** Calendar UI hosted in the content area so the app drawer stays available over it. */
    private var calendarPanelView: CalendarPanel? = null

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

    private fun hideCalendarScreen() {
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

    private fun autoDetectUnifiedPush() {
        val distributors = UnifiedPush.getDistributors(this, arrayListOf())
        val preferred = distributors.firstOrNull {
            it.contains("ntfy", ignoreCase = true) || it.contains("sunup", ignoreCase = true)
        } ?: distributors.firstOrNull()

        if (preferred != null) {
            try {
                UnifiedPush.saveDistributor(this, preferred)
                UnifiedPush.registerApp(this, INSTANCE_DEFAULT, arrayListOf(), packageName)
                unifiedPushSwitch.isChecked = true
                saveUnifiedPushEnabled(true)
                Log.d("MainActivity", "UnifiedPush auto-registered: $preferred")
            } catch (e: Throwable) {
                Log.e("MainActivity", "UnifiedPush auto-registration failed", e)
                saveUnifiedPushEnabled(false)
            }
        } else {
            saveUnifiedPushEnabled(false)
        }
    }

    private fun showAddAccountDialog() {
        val dp = resources.displayMetrics.density
        val dialogBg = getDialogBackgroundColor()
        val accentInt = currentAccentColor.toColorInt()
        val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val hintColor = if (currentTheme == "light") "#9E9E9E".toColorInt() else "#616161".toColorInt()
        val secondaryColor = if (currentTheme == "light") "#757575".toColorInt() else "#9E9E9E".toColorInt()

        val view = layoutInflater.inflate(R.layout.dialog_add_account, null)
        val dialogEmail = view.findViewById<EditText>(R.id.dialogEmailInput)
        val dialogPassword = view.findViewById<EditText>(R.id.dialogPasswordInput)
        val dialogServerUrl = view.findViewById<EditText>(R.id.dialogServerUrlInput)

        val root = view as LinearLayout
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * dp
            setColor(dialogBg)
        }
        (root.getChildAt(0) as? TextView)?.setTextColor(textColor)
        // The XML input_field_bg drawable is a hardcoded dark grey that ignores the theme
        // (hints become grey-on-grey and unreadable). Give each field a theme-aware surface.
        val fieldFill = when (currentTheme) {
            "light"  -> "#FFFFFF".toColorInt()
            "oled"   -> "#141414".toColorInt()
            "violet" -> "#241634".toColorInt()
            else     -> "#2E2E34".toColorInt()
        }
        val fieldStroke = if (currentTheme == "light") "#D0D0D4".toColorInt() else "#454552".toColorInt()
        // A clearer hint colour so the field labels (Email Address / Password / JMAP URL) read well.
        val fieldHint = if (currentTheme == "light") "#8A8A90".toColorInt() else "#B0B0BA".toColorInt()
        listOf(dialogEmail, dialogPassword, dialogServerUrl).forEach {
            it.setTextColor(textColor)
            it.setHintTextColor(fieldHint)
            it.backgroundTintList = null
            it.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * dp
                setColor(fieldFill)
                setStroke((1 * dp).toInt(), fieldStroke)
            }
            it.setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            it.compoundDrawableTintList = ColorStateList.valueOf(fieldHint)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4 * dp).toInt() }
            setPadding(0, 0, (8 * dp).toInt(), (6 * dp).toInt())
        }
        root.addView(btnRow)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val cancelBtn = TextView(this).apply {
            text = getString(R.string.action_cancel)
            textSize = 14f
            setTextColor(secondaryColor)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        val loginBtn = TextView(this).apply {
            text = getString(R.string.login_button)
            textSize = 14f
            setTextColor(accentInt)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            isClickable = true; isFocusable = true
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(loginBtn)

        loginBtn.setOnClickListener {
            val email = dialogEmail.text.toString()
            val password = dialogPassword.text.toString()
            val url = dialogServerUrl.text.toString()

            if (email.isBlank() || password.isBlank() || url.isBlank()) {
                android.widget.Toast.makeText(this, "Please fill in all fields", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.Main) {
                loginBtn.isEnabled = false
                val result = jmapClient.connect(email, password, url)
                if (result.success && result.connectedAccount != null) {
                    val newAccount = result.connectedAccount
                    val entry = AccountEntry(
                        email = newAccount.email,
                        password = newAccount.password,
                        serverUrl = url,
                        sessionUrl = newAccount.sessionUrl,
                        apiUrl = newAccount.apiUrl,
                        accountId = newAccount.accountId
                    )
                    val idx = savedAccounts.indexOfFirst { it.email.equals(newAccount.email, ignoreCase = true) }
                    if (idx >= 0) savedAccounts[idx] = entry else savedAccounts.add(entry)
                    currentAccountEmail = newAccount.email
                    loadLabels()
                    saveAccounts()
                    connectedAccount = newAccount
                    refreshInboxNow()
                    renderAccountHeader()
                    dialog.dismiss()
                } else {
                    android.widget.Toast.makeText(this@MainActivity, result.errorMessage ?: "Failed to connect", android.widget.Toast.LENGTH_LONG).show()
                    loginBtn.isEnabled = true
                }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.let { lp ->
            lp.width = (resources.displayMetrics.widthPixels * 0.9f).toInt()
            dialog.window?.attributes = lp
        }
    }

    private fun setupEmailDetailView() {
        val dp = resources.displayMetrics.density
        val barHeight = (60 * dp).toInt()
        // FrameLayout so the action row is a top overlay over the content: hiding it never
        // reflows the WebView (no flicker) and leaves the email background (no grey gap).
        emailDetailContainer =
                EmailDetailContainer(this).apply {
                    id = View.generateViewId()
                    visibility = View.GONE
                    topZoneHeight = barHeight
                    onSwipeDrag = { dx -> onDetailSwipeDrag(dx) }
                    onSwipeEnd = { dx, vx -> onDetailSwipeEnd(dx, vx) }
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            )
                    setBackgroundColor("#1F1F1F".toColorInt())
                }
        // Content column sits below the overlay bar via a top inset equal to the bar height.
        detailBody =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, barHeight, 0, 0)
                }
        detailBarHeight = barHeight
        // Pinned Gmail-style header: subject + star on top, then sender/date with
        // "to me" expander, reply and an overflow menu for the remaining actions.
        val headerWrap =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor("#1F1F1F".toColorInt())
                    minimumHeight = barHeight
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding((16 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
                }
        detailHeaderRow = headerWrap
        detailFrom =
                TextView(this).apply {
                    setTextColor("#FFFFFF".toColorInt())
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxWidth = (resources.displayMetrics.widthPixels * 0.52f).toInt()
                }

        fun detailActionIcon(iconRes: Int, desc: String, onClick: (DisplayEmail) -> Unit): ImageView =
                ImageView(this).apply {
                    setImageResource(iconRes)
                    contentDescription = desc
                    val sz = (40 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    val p = (9 * dp).toInt()
                    setPadding(p, p, p, p)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = true
                    isFocusable = true
                    background = ContextCompat.getDrawable(
                        this@MainActivity,
                        android.util.TypedValue().also {
                            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                        }.resourceId
                    )
                    setOnClickListener { currentDetailEmail?.let(onClick) }
                }

        detailReplyButton = detailActionIcon(R.drawable.ic_lucide_reply, "Reply") { startReply(it) }
        // Legacy action icons now live in the overflow menu; views kept for the tinting code.
        detailForwardButton = detailActionIcon(R.drawable.ic_lucide_forward, "Forward") { startForward(it) }
        detailArchiveButton = detailActionIcon(R.drawable.ic_lucide_archive, "Archive") { archiveDetailEmail(it) }
        detailTrashButton = detailActionIcon(R.drawable.ic_lucide_trash, "Delete") { trashDetailEmail(it) }
        detailMoveButton = detailActionIcon(R.drawable.ic_lucide_folder_input, "Move to") { moveDetailEmail(it) }
        detailStarButton = detailActionIcon(R.drawable.ic_lucide_star, "Favorite") { toggleDetailFavorite(it) }
        detailMoreButton = detailActionIcon(R.drawable.ic_lucide_more_vertical, "More") { showDetailOverflowMenu() }
        // Row of labels next to the star button
        detailLabelRowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(
                this@MainActivity,
                android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                }.resourceId
            )
            setOnClickListener { currentDetailEmail?.let { showLabelPicker(listOf(it.id)) } }
            val p = (4 * dp).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = (4 * dp).toInt()
            }
            repeat(3) {
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_lucide_tag)
                    val sz = (20 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = (2 * dp).toInt() }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = "+"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.marginEnd = (2 * dp).toInt()
                }
            })
            visibility = View.GONE
        }

        // Row 1: subject + favorite star.
        detailSubject = TextView(this).apply {
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerWrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(detailSubject)
            addView(detailLabelRowView)
            addView(detailStarButton)
        })

        // Row 2: sender (bold) + relative date in gray, "to me" expander below;
        // reply and overflow pinned at the right.
        detailDate = TextView(this).apply {
            textSize = 12f
            setTextColor("#9E9E9E".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = (8 * dp).toInt() }
        }
        detailToText = TextView(this).apply {
            textSize = 12f
            setTextColor("#9E9E9E".toColorInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showDetailAddressDialog() }
        }
        val senderCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(detailFrom)
                addView(detailDate)
            })
            addView(detailToText)
        }
        headerWrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (6 * dp).toInt() }
            addView(senderCol)
            addView(detailReplyButton)
            addView(detailTrashButton)
            addView(detailMoreButton)
        })


        detailWebView =
                android.webkit.WebView(this).apply {
                    layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                    setBackgroundColor(Color.WHITE)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isNestedScrollingEnabled = false
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
        detailWebView.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                showLinkConfirmationDialog(request.url.toString())
                return true
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView,
                url: String
            ): Boolean {
                showLinkConfirmationDialog(url)
                return true
            }
            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                super.onPageFinished(view, url)
            }
        }
        detailBody.addView(detailWebView)
        // Weighted spacer: when the body is shorter than the viewport (fillViewport stretches
        // detailBody to viewport height) this absorbs the slack and pushes the attachment
        // footer to the bottom. For tall bodies there is no slack, so attachments follow content.
        detailBody.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
        detailScroll = androidx.core.widget.NestedScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(detailBody)
        }
        // Auto-hide the action row when scrolling down, reveal it when scrolling up.
        val scrollThreshold = (24 * dp).toInt()
        detailScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            when {
                dy > 4 && scrollY > scrollThreshold && !detailBarHidden -> setDetailBarHidden(true)
                dy < -4 && detailBarHidden -> setDetailBarHidden(false)
            }
        }
        emailDetailContainer.addView(detailScroll)
        emailDetailContainer.addView(headerWrap)
        mailboxContainer.addView(emailDetailContainer)
    }

    private fun setDetailBarHidden(hidden: Boolean) {
        if (detailBarHidden == hidden) return
        val now = System.currentTimeMillis()
        if (now - detailBarLastToggleMs < 200) return
        detailBarLastToggleMs = now
        detailBarHidden = hidden
        detailHeaderRow.animate().cancel()
        if (hidden) {
            // Cache the measured height so the reveal animation has a distance to travel.
            detailHeaderRow.height.takeIf { it > 0 }?.let { detailBarHeight = it }
            detailHeaderRow.animate().translationY(-detailBarHeight.toFloat()).alpha(0f).setDuration(160).withEndAction {
                detailHeaderRow.visibility = View.GONE
            }.start()
            android.animation.ValueAnimator.ofInt(detailBarHeight, 0).apply {
                duration = 160
                addUpdateListener { detailBody.setPadding(0, it.animatedValue as Int, 0, 0) }
                start()
            }
        } else {
            detailHeaderRow.visibility = View.VISIBLE
            detailHeaderRow.translationY = -detailBarHeight.toFloat()
            detailHeaderRow.alpha = 0f
            detailHeaderRow.animate().translationY(0f).alpha(1f).setDuration(160).start()
            android.animation.ValueAnimator.ofInt(0, detailBarHeight).apply {
                duration = 160
                addUpdateListener { detailBody.setPadding(0, it.animatedValue as Int, 0, 0) }
                start()
            }
        }
    }

    private fun detailSwipeTarget(forward: Boolean): DisplayEmail? {
        val current = currentDetailEmail ?: return null
        val idx = emails.indexOfFirst { it.id == current.id }
        if (idx < 0) return null
        return if (forward) emails.getOrNull(idx + 1) else emails.getOrNull(idx - 1)
    }

    /** Lazily builds the sliding preview panel (a second WebView) used during detail swipes. */
    private fun ensureDetailPreviewPanel(): LinearLayout {
        detailPreviewPanel?.let { return it }
        val wv = android.webkit.WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, detailBarHeight, 0, 0)
            visibility = View.GONE
            // The preview is display-only: swallow touches while it briefly overlays.
            setOnTouchListener { _, _ -> true }
            addView(wv)
        }
        // Above detailBody, below the pinned header row.
        emailDetailContainer.addView(panel, 1)
        detailPreviewPanel = panel
        detailPreviewWebView = wv
        return panel
    }

    /** Loads [target]'s content into the preview (cached body, or the shimmer skeleton). */
    private fun prepareDetailPreview(target: DisplayEmail, forward: Boolean) {
        val key = "${target.id}:$forward"
        if (detailPreviewKey == key) return
        detailPreviewKey = key
        val panel = ensureDetailPreviewPanel()
        val wv = detailPreviewWebView ?: return
        val bg = when (currentTheme) {
            "oled" -> "#000000"
            "light" -> "#ffffff"
            else -> "#1a1a1a"
        }.toColorInt()
        panel.setBackgroundColor(bg)
        wv.setBackgroundColor(bg)
        wv.settings.blockNetworkImage =
            !getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("load_images", false)
        val html = if (target.fullBody.isNotBlank())
            buildHtmlContent(target.fullBody)
        else
            buildSkeletonHtml()
        wv.loadDataWithBaseURL("https://jmapjolt.invalid/email/", html, "text/html", "UTF-8", null)
    }

    /** Content follows the finger; the adjacent email slides in alongside it (no empty gap). */
    private fun onDetailSwipeDrag(dx: Float) {
        if (detailSwipeAnimating) return
        val forward = dx < 0
        val target = detailSwipeTarget(forward)
        val resistance = if (target == null) 0.25f else 1f
        detailBody.translationX = dx * resistance
        val w = detailBody.width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()
        if (target != null) {
            prepareDetailPreview(target, forward)
            detailPreviewPanel?.let {
                it.visibility = View.VISIBLE
                it.alpha = 1f
                it.translationX = dx + if (forward) w else -w
            }
        } else {
            detailPreviewPanel?.visibility = View.GONE
            detailPreviewKey = null
        }
    }

    private fun onDetailSwipeEnd(dx: Float, velocityX: Float) {
        if (detailSwipeAnimating) return
        val w = detailBody.width.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.toFloat()
        val dp = resources.displayMetrics.density
        val forward = dx < 0
        val target = detailSwipeTarget(forward)
        val flung = kotlin.math.abs(velocityX) > 800 * dp &&
            (velocityX < 0) == forward  // fling must match the drag direction
        val shouldComplete = target != null && (kotlin.math.abs(dx) > w * 0.30f || flung)

        if (!shouldComplete) {
            detailBody.animate()
                .translationX(0f)
                .setDuration(240)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                .start()
            detailPreviewPanel?.let { p ->
                if (p.visibility == View.VISIBLE) {
                    p.animate()
                        .translationX(if (forward) w else -w)
                        .setStartDelay(0)
                        .setDuration(240)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                        .withEndAction { p.visibility = View.GONE; detailPreviewKey = null }
                        .start()
                }
            }
            return
        }

        detailSwipeAnimating = true
        val exitX = if (forward) -w else w
        prepareDetailPreview(target!!, forward)
        val panel = ensureDetailPreviewPanel().also {
            it.visibility = View.VISIBLE
            // Pure fling with no drag events yet: start from fully off-screen.
            if (detailBody.translationX == 0f) it.translationX = if (forward) w else -w
        }
        // Continue at roughly the finger's speed: duration from remaining distance.
        val remaining = kotlin.math.abs(exitX - detailBody.translationX)
        val exitDuration = (remaining / w * 220).toLong().coerceIn(90, 220)
        panel.animate()
            .translationX(0f)
            .setStartDelay(0)
            .setDuration(exitDuration)
            .setInterpolator(android.view.animation.LinearInterpolator())
            .start()
        detailBody.animate()
            .translationX(exitX)
            .setStartDelay(0)
            .setDuration(exitDuration)
            .setInterpolator(android.view.animation.LinearInterpolator())
            .withEndAction {
                detailBody.translationX = 0f
                showEmailDetail(target, fromSwipe = true)
                // Keep the preview overlaid while the real WebView paints the same
                // content underneath, then fade it away: no skeleton flash, no gap.
                panel.animate()
                    .alpha(0f)
                    .setStartDelay(140)
                    .setDuration(160)
                    .withEndAction {
                        panel.visibility = View.GONE
                        panel.alpha = 1f
                        detailPreviewKey = null
                        detailSwipeAnimating = false
                    }
                    .start()
            }
            .start()
    }

    /** Overflow menu (3 dots) on the detail header: actions that used to be inline icons. */
    private fun showDetailOverflowMenu() {
        val email = currentDetailEmail ?: return
        val inArchive = selectedFolder == R.id.nav_archive
        showSettingsDropdown(
            detailMoreButton,
            listOf(
                if (inArchive) "Unarchive" else getString(R.string.swipe_action_archive),
                "Forward",
                "Move to",
                "Label"
            ),
            -1,
            icons = listOf(
                if (inArchive) R.drawable.ic_lucide_archive_restore else R.drawable.ic_lucide_archive,
                R.drawable.ic_lucide_forward,
                R.drawable.ic_lucide_folder_input,
                R.drawable.ic_lucide_tag
            )
        ) { idx ->
            when (idx) {
                0 -> if (inArchive) unarchiveDetailEmail(email) else archiveDetailEmail(email)
                1 -> startForward(email)
                2 -> moveDetailEmail(email)
                3 -> showLabelPicker(listOf(email.id))
            }
        }
    }

    internal fun updateDetailLabelIcon() {
        if (!::detailLabelRowView.isInitialized) return
        val email = currentDetailEmail ?: return
        val rowLabels = labelsOf(email)
        val owned = ownsEmail(email)
        // Not owned: labels are read-only. Show a single gray tag as a disabled
        // affordance (tapping routes through the guarded picker → "switch account").
        val grayTint = if (currentTheme == "light") "#BDBDBD".toColorInt() else "#616161".toColorInt()
        if (rowLabels.isEmpty() && owned) {
            detailLabelRowView.visibility = View.GONE
            return
        }
        detailLabelRowView.visibility = View.VISIBLE
        for (i in 0..2) {
            val iv = detailLabelRowView.getChildAt(i) as ImageView
            val l = rowLabels.getOrNull(i)
            when {
                l != null -> {
                    iv.visibility = View.VISIBLE
                    iv.imageTintList = ColorStateList.valueOf(
                        if (owned) l.colorHex.toColorInt() else grayTint
                    )
                }
                // No labels but not owned → one gray tag at slot 0 as the locked affordance.
                i == 0 && !owned -> {
                    iv.visibility = View.VISIBLE
                    iv.imageTintList = ColorStateList.valueOf(grayTint)
                }
                else -> iv.visibility = View.GONE
            }
        }
        (detailLabelRowView.getChildAt(3) as TextView).apply {
            visibility = if (rowLabels.size > 3) View.VISIBLE else View.GONE
            val isLight = currentTheme == "light"
            setTextColor(if (isLight) "#757575".toColorInt() else "#9E9E9E".toColorInt())
        }
    }

    /** Moves an archived email back to the inbox (detail-view counterpart of swipe unarchive). */
    private fun unarchiveDetailEmail(email: DisplayEmail) {
        val acc = resolveAccountFor(email) ?: connectedAccount ?: return
        updateFolderCachesForInbox(email)
        closeEmailDetail()
        removeEmailsAnimated(listOf(email.id))
        saveEmailCache()
        Snackbar.make(drawerLayout, "Moved to Inbox", Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val inboxId = resolveMailboxIdByRole(acc, "inbox")
                if (inboxId != null) {
                    jmapClient.setMailbox(acc, email.id, inboxId)
                    BackgroundEmailSyncReceiver.addToBaseline(this@MainActivity, acc.email, listOf(email.id))
                }
            } catch (e: Exception) { Log.e(TAG, "detail unarchive failed", e) }
        }
    }

    /** Re-syncs body inset and the swipe zone with the (content-dependent) header height. */
    private fun syncDetailHeaderHeight() {
        detailHeaderRow.post {
            val h = detailHeaderRow.height
            if (h > 0 && !detailBarHidden) {
                detailBarHeight = h
                detailBody.setPadding(0, h, 0, 0)
                detailPreviewPanel?.setPadding(0, h, 0, 0)
                emailDetailContainer.topZoneHeight = h
            }
        }
    }

    /** "to me ▾" tap: floating popup card with full addresses; tap anywhere outside to dismiss. */
    private fun showDetailAddressDialog() {
        val email = currentDetailEmail ?: return
        val dp = resources.displayMetrics.density
        val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val subColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val ph = (16 * dp).toInt()
            val pv = (12 * dp).toInt()
            setPadding(ph, pv, ph, pv)
            minimumWidth = (220 * dp).toInt()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * dp
                setColor(getDialogBackgroundColor())
            }
            elevation = 8 * dp
        }
        fun row(label: String, value: String) {
            card.addView(TextView(this).apply {
                text = label
                textSize = 10f
                letterSpacing = 0.08f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(subColor)
            })
            card.addView(TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(textColor)
                setTextIsSelectable(true)
                maxWidth = (resources.displayMetrics.widthPixels * 0.75f).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
            })
        }
        row("FROM", listOf(email.from, email.fromEmail).filter { it.isNotBlank() }.distinct().joinToString(" · "))
        row("TO", email.toEmail.ifBlank { email.accountEmail.ifBlank { "me" } })
        if (email.receivedAt > 0) {
            row("DATE", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(email.receivedAt)))
        }

        val pw = android.widget.PopupWindow(
            card,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 10 * dp
            isOutsideTouchable = true
        }
        pw.showAsDropDown(detailToText, 0, (4 * dp).toInt())
        // MD3 menu motion: scale-in from the anchor corner with a fade.
        card.alpha = 0f
        card.scaleX = 0.86f
        card.scaleY = 0.78f
        card.post {
            card.pivotX = card.width * 0.15f
            card.pivotY = 0f
            card.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
                .start()
        }
    }

    internal fun sanitizeEmailHtml(html: String): String {
        return html
            .replace(Regex("<script[\\s>][\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<script\\s*/?>", RegexOption.IGNORE_CASE), "")
            // Embedding/navigation vectors: strip the tags, keep inner text content.
            .replace(Regex("</?(iframe|object|embed|frame|frameset|base|applet|form)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(\s)on[a-zA-Z]+\s*=\s*"[^"]*""""), "$1")
            .replace(Regex("""(\s)on[a-zA-Z]+\s*=\s*'[^']*'"""), "$1")
            .replace(Regex("""(\s)on[a-zA-Z]+\s*=[^\s>]+"""), "$1")
            .replace(Regex("""(\s)srcdoc\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE), "$1")
            // Block script-bearing URI schemes; data: stays allowed in src so inline images keep working.
            .replace(Regex("""(href|action|formaction)\s*=\s*["']?\s*(javascript|data|vbscript):[^"'\s>]*""", RegexOption.IGNORE_CASE), "$1=\"#\"")
            .replace(Regex("""(src|background)\s*=\s*["']?\s*(javascript|vbscript):[^"'\s>]*""", RegexOption.IGNORE_CASE), "$1=\"\"")
            .replace(Regex("""expression\s*\(""", RegexOption.IGNORE_CASE), "no-expression(")
    }

    /** Heuristic: true when the body carries real HTML markup (full document or fragment). */
    private fun looksLikeHtml(body: String): Boolean = HTML_MARKUP_REGEX.containsMatchIn(body)

    private fun buildSkeletonHtml(): String {
        val isDark = currentTheme == "gray" || currentTheme == "oled" || currentTheme == "violet"
        val bg = when (currentTheme) {
            "light"  -> "#F6F6F8"
            "oled"   -> "#000000"
            "violet" -> "#160E24"
            else     -> "#212126"
        }
        val base = if (currentTheme == "oled") "#111111" else if (isDark) "#2a2a2a" else "#e0e0e0"
        val shine = if (currentTheme == "oled") "#1e1e1e" else if (isDark) "#3a3a3a" else "#f0f0f0"
        return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:$bg;padding:20px}
.s{background:linear-gradient(90deg,$base 25%,$shine 50%,$base 75%);background-size:300% 100%;animation:sh 1.4s infinite;border-radius:6px;height:13px;margin-bottom:14px}
.w100{width:100%}.w85{width:85%}.w70{width:70%}.w55{width:55%}.w40{width:40%}.w30{width:30%}
.gap{height:24px}
@keyframes sh{0%{background-position:100% 0}100%{background-position:-100% 0}}
</style></head><body>
<div class="s w85"></div>
<div class="s w70"></div>
<div class="s w100"></div>
<div class="gap"></div>
<div class="s w100"></div>
<div class="s w85"></div>
<div class="s w55"></div>
<div class="gap"></div>
<div class="s w100"></div>
<div class="s w70"></div>
<div class="s w100"></div>
<div class="s w40"></div>
<div class="gap"></div>
<div class="s w85"></div>
<div class="s w30"></div>
</body></html>"""
    }

    internal fun buildHtmlContent(rawBodyIn: String, subject: String = ""): String {
        // Inline cid: images are shown as attachment cards below the body; strip the in-body
        // <img src="cid:..."> tags so a broken-image placeholder is not rendered in the content.
        val rawBody = rawBodyIn.replace(
            Regex("<img\\b[^>]*\\bsrc\\s*=\\s*[\"']cid:[^>]*>", RegexOption.IGNORE_CASE), ""
        )
        val isDark = currentTheme == "gray" || currentTheme == "oled" || currentTheme == "violet"
        val bgColor = when (currentTheme) {
            "light"  -> "#F6F6F8"
            "oled"   -> "#000000"
            "violet" -> "#160E24"
            else     -> "#212126"
        }
        val textColor = if (isDark) "#e0e0e0" else "#212121"
        val linkColor = currentAccentColor

        // Subject is rendered inside the scrollable WebView content so it scrolls away,
        // while the action row above stays pinned.
        val subjectHeading = if (subject.isNotBlank())
            "<div style=\"font-size:18px;font-weight:700;color:$textColor;padding:14px 12px 6px;line-height:1.3\">" +
                android.text.TextUtils.htmlEncode(subject) + "</div>"
        else ""

        val darkCss = if (isDark) """
            <style id="jj-dark">
            html,body{background-color:$bgColor!important;color:$textColor!important}
            *:not(img):not(svg):not(video){background-color:transparent!important;color:$textColor!important;border-color:#444!important}
            a,a *{color:$linkColor!important}
            table{background-color:$bgColor!important}
            td,th,tr{background-color:transparent!important;color:$textColor!important}
            img{filter:brightness(.9) contrast(1.05)}
            </style>
        """.trimIndent() else ""

        val isFullDoc = rawBody.contains("<html", ignoreCase = true)
        val isFragment = !isFullDoc && looksLikeHtml(rawBody)
        return if (isFullDoc) {
            val body = sanitizeEmailHtml(rawBody)
            var html = body
            if (!html.contains("viewport", ignoreCase = true))
                html = html.replaceFirst("<head", "<head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\">", ignoreCase = true)
            // Insert the subject right after the opening <body> tag (fallback: prepend).
            if (subjectHeading.isNotEmpty()) {
                val bodyIdx = html.indexOf("<body", ignoreCase = true)
                val gt = if (bodyIdx >= 0) html.indexOf('>', bodyIdx) else -1
                html = if (gt >= 0) html.substring(0, gt + 1) + subjectHeading + html.substring(gt + 1)
                       else subjectHeading + html
            }
            if (isDark) html
                .replaceFirst("<html", "<html style=\"background-color:$bgColor\"", ignoreCase = true)
                .replaceFirst("</head>", "<meta name=\"color-scheme\" content=\"dark\">$darkCss</head>", ignoreCase = true)
                .replaceFirst("<body", "<body style=\"background-color:$bgColor;color:$textColor\"", ignoreCase = true)
            else html
        } else if (isFragment) {
            // HTML fragment (no <html> root, e.g. JMAP htmlBody parts or this app's replies).
            // Wrap it in a styled document and render as HTML instead of escaping the markup.
            val body = sanitizeEmailHtml(rawBody)
            val colorScheme = if (isDark) "dark" else "light"
            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\"><meta name=\"color-scheme\" content=\"$colorScheme\">$darkCss<style>body{color:$textColor;background:$bgColor;font-family:-apple-system,sans-serif;word-wrap:break-word;padding:12px;margin:0;max-width:100%;box-sizing:border-box}img{max-width:100%;height:auto}a{color:$linkColor}</style></head><body>$subjectHeading$body</body></html>"
        } else {
            // Plain text: escape HTML entities, then style quoted lines (lines starting with ">")
            val quoteColor = if (isDark) "#616161" else "#9E9E9E"
            val escaped = rawBody
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val lines = escaped.split("\n").joinToString("<br>") { line ->
                if (line.trimStart().startsWith("&gt;"))
                    "<span style=\"color:$quoteColor\">$line</span>"
                else
                    line
            }
            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\">$darkCss<style>body{color:$textColor;background:$bgColor;font-family:-apple-system,sans-serif;word-wrap:break-word;padding:12px;margin:0;max-width:100%;box-sizing:border-box}img{max-width:100%;height:auto}</style></head><body>$subjectHeading$lines</body></html>"
        }
    }

    private fun updateDetailStarIcon(isFavorite: Boolean) {
        val color = if (isFavorite) currentAccentColor.toColorInt()
                    else if (currentTheme == "light") "#9E9E9E".toColorInt() else "#888888".toColorInt()
        detailStarButton.imageTintList = ColorStateList.valueOf(color)
    }

    private fun toggleDetailFavorite(email: DisplayEmail) {
        val newFav = !email.isFavorite
        email.isFavorite = newFav
        emails.find { it.id == email.id }?.isFavorite = newFav
        baseEmails.find { it.id == email.id }?.isFavorite = newFav
        optimisticFavorite[email.id] = newFav
        updateFolderCachesForFavorite(email.copy(), newFav)
        updateDetailStarIcon(newFav)
        detailStarButton.animateTap()
        // Only one row changed: a targeted rebind avoids re-running favicon
        // jobs and bind allocations for every visible row.
        val changedPos = emails.indexOfFirst { it.id == email.id }
        if (changedPos >= 0) emailAdapter.notifyItemChanged(changedPos)
        else emailAdapter.notifyDataSetChanged()
        saveEmailCache()
        val acc = resolveAccountFor(email) ?: connectedAccount ?: return
        lifecycleScope.launch {
            try { jmapClient.setFavorite(acc, email.id, newFav) }
            catch (e: Exception) { Log.e(TAG, "detail star failed", e) }
        }
    }

    private fun archiveDetailEmail(email: DisplayEmail) {
        val acc = resolveAccountFor(email) ?: connectedAccount ?: return
        updateFolderCachesForMove(email, R.id.nav_archive)
        closeEmailDetail()
        removeEmailsAnimated(listOf(email.id))
        saveEmailCache()
        Snackbar.make(drawerLayout, "Archived", Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val archiveId = resolveOrCreateArchive(acc)
                if (archiveId != null) jmapClient.setMailbox(acc, email.id, archiveId)
            } catch (e: Exception) { Log.e(TAG, "detail archive failed", e) }
        }
    }

    private fun trashDetailEmail(email: DisplayEmail) {
        val acc = resolveAccountFor(email) ?: connectedAccount ?: return
        // Deleting from Trash is permanent.
        if (selectedFolder == R.id.nav_trash) {
            closeEmailDetail()
            confirmPermanentDelete(acc, listOf(email.id))
            return
        }
        updateFolderCachesForMove(email, R.id.nav_trash)
        closeEmailDetail()
        removeEmailsAnimated(listOf(email.id))
        saveEmailCache()
        Snackbar.make(drawerLayout, "Moved to Trash", Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val trashId = jmapClient.resolveMailboxIdByRole(acc, "trash")
                if (trashId != null) jmapClient.setMailbox(acc, email.id, trashId)
            } catch (e: Exception) { Log.e(TAG, "detail trash failed", e) }
        }
    }

    private fun moveDetailEmail(email: DisplayEmail) {
        val acc = resolveAccountFor(email) ?: connectedAccount ?: return
        val ids = listOf(email.id)
        val excludedRoles = buildList {
            add("drafts"); add("trash")
            if (selectedFolder == R.id.nav_inbox || selectedFolder == R.id.nav_unified_inbox) add("inbox")
            // Favorites live in the inbox already: moving there would be a no-op.
            if (selectedFolder == R.id.nav_favourite) add("inbox")
        }
        fun present(mailboxes: List<JMapClient.MailboxInfo>) {
            val filtered = mailboxes.filter { it.role?.lowercase() !in excludedRoles }
            // Stay on the email while picking; leave it only once a folder is chosen.
            if (filtered.isNotEmpty())
                showMoveLabelPicker(filtered, ids, null, setOf("sent"), onPicked = { closeEmailDetail() })
        }
        val cached = mailboxCache
        if (cached != null) {
            present(cached)
            lifecycleScope.launch {
                runCatching { jmapClient.fetchMailboxes(acc) }.getOrNull()?.let { mailboxCache = it }
            }
        } else {
            lifecycleScope.launch {
                val mailboxes = jmapClient.fetchMailboxes(acc)
                mailboxCache = mailboxes
                present(mailboxes)
            }
        }
    }

    internal fun closeEmailDetail() {
        // Hard-clear WebView immediately so next open starts blank
        detailWebView.stopLoading()
        detailWebView.loadDataWithBaseURL("https://jmapjolt.invalid/email/","", "text/html", "UTF-8", null)
        currentDetailEmail = null
        emailDetailContainer.animateScreenOutBack()
        mailSwipeRefresh.visibility = View.VISIBLE
        fabCompose.animateFabIn()
        isShowingEmailDetail = false
        setDrawerIndicator(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        drawerToggle.syncState()
        applyNavIconTint(getOnAccentColor())
        updateCustomTopBar(getCurrentMailboxTitle(), inMailbox = true)
        if (isSearchActive) searchChipsScroll.visibility = View.VISIBLE
    }

    /** Captures a widget tap so the target email opens once its data is available. */
    private fun handleWidgetIntent(intent: Intent?) {
        val id = intent?.getStringExtra(InboxWidgetProvider.EXTRA_OPEN_EMAIL_ID) ?: return
        if (id.isBlank()) return
        pendingWidgetEmailId = id
        pendingWidgetAccount = intent.getStringExtra(InboxWidgetProvider.EXTRA_OPEN_ACCOUNT)
        widgetSwitchAttempted = false
        // Consume so a config change or re-delivery doesn't reopen it.
        intent.removeExtra(InboxWidgetProvider.EXTRA_OPEN_EMAIL_ID)
        // On a warm start data is already loaded; on cold start the session-restore
        // load path will call tryOpenPendingWidgetEmail once emails arrive.
        if (baseEmails.isNotEmpty()) tryOpenPendingWidgetEmail()
    }

    /** Opens a pending widget email when its account is active and the message is loaded. */
    private fun tryOpenPendingWidgetEmail() {
        val id = pendingWidgetEmailId ?: return
        val account = pendingWidgetAccount
        // Different account than the one shown: switch to it once, then wait for its load.
        if (!account.isNullOrBlank() && account != WidgetSupport.UNIFIED &&
            !account.equals(currentAccountEmail, ignoreCase = true)) {
            if (!widgetSwitchAttempted) {
                widgetSwitchAttempted = true
                savedAccounts.firstOrNull { it.email.equals(account, ignoreCase = true) }
                    ?.let { switchToSavedAccount(it, forceInbox = true) }
            }
            return
        }
        val match = baseEmails.firstOrNull { it.id == id } ?: return
        pendingWidgetEmailId = null
        pendingWidgetAccount = null
        if (!isShowingEmailDetail) showEmailDetail(match)
    }

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

    private fun prefetchEmailBody(email: DisplayEmail) {
        if (email.fullBody.isNotBlank()) return
        if (!prefetchingIds.add(email.id)) return
        val account = resolveAccountFor(email) ?: run { prefetchingIds.remove(email.id); return }
        lifecycleScope.launch {
            try {
                val fresh = jmapClient.fetchEmailsById(account, listOf(email.id)).firstOrNull()
                if (fresh != null && fresh.fullBody.isNotBlank()) {
                    val updated = email.copy(fullBody = fresh.fullBody, attachments = fresh.attachments)
                    val idx = emails.indexOfFirst { it.id == email.id }
                    if (idx >= 0) {
                        emails[idx] = updated
                        val bi = baseEmails.indexOfFirst { it.id == email.id }
                        if (bi >= 0) baseEmails[bi] = updated
                    }
                }
            } catch (_: Exception) {
                // Silent failure — will retry on open
            } finally {
                prefetchingIds.remove(email.id)
            }
        }
    }

    private fun showSettingsScreen() {
        hideCalendarScreen()
        onboardingContainer.visibility = View.GONE
        loginContainer.visibility = View.GONE
        mailboxContainer.visibility = View.GONE
        settingsContainer.visibility = View.VISIBLE
        settingsContainer.animateScreenIn()
        fabCompose.animateFabOut()
        customTopBar.visibility = View.VISIBLE
        currentSettingsSection = SettingsSection.ROOT
        invalidateOptionsMenu()
        setDrawerIndicator(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        drawerToggle.syncState()
        updateTopBarState()
        showSettingsMenuRoot()
        loadUnifiedPushPreferences()
        rebuildDrawerMenu()
    }

    private fun bindSettingsMenuNavigation() {
        loadImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveGeneralPreferences()
            if (isShowingEmailDetail) {
                detailWebView.settings.blockNetworkImage = !isChecked
                if (isChecked) detailWebView.reload()
            }
        }
        loadFaviconsSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showThemedConfirmDialog(
                    title = "Auto-load favicons",
                    message = "This feature uses DuckDuckGo's external service (icons.duckduckgo.com) to fetch favicons for email senders. No personal data is sent, only the domain name.",
                    confirmLabel = "Enable"
                ) {
                    saveGeneralPreferences()
                    emailAdapter.loadFaviconsEnabled = true
                    emailAdapter.notifyDataSetChanged()
                }
                loadFaviconsSwitch.isChecked = false
            } else {
                saveGeneralPreferences()
                emailAdapter.loadFaviconsEnabled = false
                emailAdapter.notifyDataSetChanged()
            }
        }

        settingsGeneralHeader.setOnClickListener {
            toggleSettingsSection(settingsGeneralContent, settingsGeneralChevron)
        }
        settingsLabelsHeader.setOnClickListener {
            toggleSettingsSection(settingsLabelsContent, settingsLabelsChevron)
        }
        settingsThemeHeader.setOnClickListener {
            toggleSettingsSection(settingsThemeContent, settingsThemeChevron)
        }
        settingsUnifiedPushHeader.setOnClickListener {
            toggleSettingsSection(settingsUnifiedPushContent, settingsUnifiedPushChevron)
        }
        val settingsCalendarHeader = findViewById<LinearLayout>(R.id.settingsCalendarHeader)
        val settingsCalendarContent = findViewById<LinearLayout>(R.id.settingsCalendarContent)
        settingsCalendarChevron = findViewById(R.id.settingsCalendarChevron)
        settingsImportIcsRow = findViewById(R.id.settingsImportIcsRow)
        settingsExportIcsRow = findViewById(R.id.settingsExportIcsRow)
        settingsCalendarHeader.setOnClickListener {
            toggleSettingsSection(settingsCalendarContent, settingsCalendarChevron)
        }
        settingsCalProviderDropdown.setOnClickListener {
            val options = listOf(
                getString(R.string.settings_cal_provider_jmap),
                getString(R.string.settings_cal_provider_davx5))
            val current = if (CalendarPrefs.provider(this) == CalendarPrefs.Provider.DAVX5) 1 else 0
            showSettingsDropdown(settingsCalProviderDropdown, options, current) { idx ->
                val chosen = if (idx == 1) CalendarPrefs.Provider.DAVX5 else CalendarPrefs.Provider.JMAP
                CalendarPrefs.setProvider(this, chosen)
                updateCalProviderUi()
                onCalendarProviderChosen(chosen)
            }
        }
        settingsCalAddProviderButton.setOnClickListener { CalendarDavx5.launch(this@MainActivity) }
        calendarEnabledSwitch.isChecked = CalendarPrefs.isEnabled(this)
        calendarEnabledSwitch.setOnCheckedChangeListener { _, enabled ->
            CalendarPrefs.setEnabled(this, enabled)
            findViewById<LinearLayout>(R.id.settingsCalOptions).visibility =
                if (enabled) View.VISIBLE else View.GONE
            if (!enabled && calendarPanelView?.visibility == View.VISIBLE) showMailboxScreen()
            navigationView.post { rebuildDrawerMenu() }
        }
        findViewById<LinearLayout>(R.id.settingsCalOptions).visibility =
            if (CalendarPrefs.isEnabled(this)) View.VISIBLE else View.GONE
        updateCalProviderUi()
        settingsImportIcsRow.setOnClickListener {
            runCatching { importIcsLauncher.launch(arrayOf("text/calendar", "*/*")) }
        }
        settingsExportIcsRow.setOnClickListener {
            runCatching { exportIcsLauncher.launch("calendar-${System.currentTimeMillis()}.ics") }
        }
        settingsInfoRow.setOnClickListener { showAboutDialog() }
    }

    /** Requests READ/WRITE_CALENDAR; invokes [onResult] once the user responds. */
    internal fun requestCalendarPermissions(onResult: () -> Unit) {
        calendarPermissionCallback = onResult
        calendarPermissionLauncher.launch(arrayOf(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR
        ))
    }

    private var calendarPermissionCallback: (() -> Unit)? = null
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> calendarPermissionCallback?.invoke(); calendarPermissionCallback = null }

    /** Reflects the selected calendar provider in the dropdown text, hint, add button + account. */
    private fun updateCalProviderUi() {
        val isDavx5 = CalendarPrefs.provider(this) == CalendarPrefs.Provider.DAVX5
        val accent = currentAccentColor.toColorInt()
        settingsCalProviderText.text = getString(
            if (isDavx5) R.string.settings_cal_provider_davx5
            else R.string.settings_cal_provider_jmap)
        findViewById<TextView>(R.id.settingsCalProviderHint)?.text = getString(
            if (isDavx5) R.string.settings_cal_provider_hint_davx5
            else R.string.settings_cal_provider_hint_jmap)
        settingsCalAddProviderButton.visibility = if (isDavx5) View.VISIBLE else View.GONE
        val accountText = findViewById<TextView>(R.id.settingsCalProviderAccount)
        val connected = if (isDavx5 && CalendarProvider.hasReadPermission(this)) {
            CalendarProvider.calendars(this)
                .map { it.accountName }
                .filter { it.isNotBlank() && !it.equals("LOCAL", ignoreCase = true) }
                .distinct()
        } else emptyList()
        if (connected.isEmpty()) {
            accountText?.visibility = View.GONE
        } else {
            accountText?.text = getString(R.string.settings_cal_connected, connected.joinToString(", "))
            accountText?.visibility = View.VISIBLE
        }
    }

    /** Handles a provider switch: warn here (not in the calendar tab) when the choice can't sync. */
    private fun onCalendarProviderChosen(provider: CalendarPrefs.Provider) {
        when (provider) {
            CalendarPrefs.Provider.DAVX5 ->
                if (!CalendarProvider.hasReadPermission(this)) {
                    requestCalendarPermissions { updateCalProviderUi() }
                }
            CalendarPrefs.Provider.JMAP -> {
                val account = CalendarAccount.current(this) ?: run {
                    showInAppMessage(getString(R.string.calendar_jmap_unsupported)); return
                }
                lifecycleScope.launch {
                    val result = CalendarSync.sync(applicationContext, account)
                    if (!result.supported) showInAppMessage(getString(R.string.calendar_jmap_unsupported))
                }
            }
        }
    }

    private fun doImportIcs(uri: android.net.Uri) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() } ?: return@runCatching 0
                    val events = CalendarIcs.parse(text, "local")
                    events.forEach { CalendarStore.upsert(applicationContext, it) }
                    events.size
                }.getOrDefault(-1)
            }
            if (count >= 0) {
                CalendarReminderScheduler.reschedule(applicationContext)
                calendarPanelView?.refresh()
                showInAppMessage("Imported $count event(s)")
            } else showInAppMessage("Import failed")
        }
    }

    private fun doExportIcs(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ics = CalendarIcs.toIcs(CalendarStore.active(applicationContext))
                    contentResolver.openOutputStream(uri)?.use { it.write(ics.toByteArray()) }
                    true
                }.getOrDefault(false)
            }
            showInAppMessage(if (ok) "Calendar exported" else "Export failed")
        }
    }

    /** App-styled bottom in-app message (matches the snackbars used elsewhere). */
    private fun showInAppMessage(text: String) {
        com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content), text,
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).show()
    }

    private fun toggleSettingsSection(content: LinearLayout, chevron: ImageView) {
        val open = content.visibility != View.VISIBLE
        chevron.animate().rotation(if (open) 180f else 0f).setDuration(220).start()
        if (open) {
            // Expand: measure target height, then grow from 0 with a fade.
            content.visibility = View.VISIBLE
            content.measure(
                View.MeasureSpec.makeMeasureSpec(settingsContainer.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val target = content.measuredHeight
            content.layoutParams.height = 0
            content.alpha = 0f
            android.animation.ValueAnimator.ofInt(0, target).apply {
                duration = 260
                interpolator = android.view.animation.DecelerateInterpolator(2f)
                addUpdateListener {
                    content.layoutParams.height = it.animatedValue as Int
                    content.alpha = it.animatedFraction
                    content.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        content.alpha = 1f
                        content.requestLayout()
                    }
                })
                start()
            }
        } else {
            // Collapse: shrink from current height to 0 with a fade.
            val start = content.height
            android.animation.ValueAnimator.ofInt(start, 0).apply {
                duration = 220
                interpolator = android.view.animation.AccelerateInterpolator(1.5f)
                addUpdateListener {
                    content.layoutParams.height = it.animatedValue as Int
                    content.alpha = 1f - it.animatedFraction
                    content.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        content.visibility = View.GONE
                        content.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        content.alpha = 1f
                        content.requestLayout()
                    }
                })
                start()
            }
        }
    }

    private fun showAboutDialog() {
        val dp = resources.displayMetrics.density
        val bgColor = getDialogBackgroundColor()
        val textColor = if (currentTheme == "light") "#212121".toColorInt() else Color.WHITE
        val subColor = if (currentTheme == "light") "#757575".toColorInt() else "#BDBDBD".toColorInt()

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(bgColor)
            }

            addView(ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt()).also {
                    it.bottomMargin = (16 * dp).toInt()
                }
                setImageResource(R.mipmap.ic_launcher_foreground)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                setTextColor(textColor)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (4 * dp).toInt() }
            })
            addView(TextView(this@MainActivity).apply {
                text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"
                setTextColor(subColor)
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (24 * dp).toInt() }
            })
            val accentInt = currentAccentColor.toColorInt()
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.about_source_code)
                setTextColor(accentInt)
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId.let { ContextCompat.getDrawable(this@MainActivity, it) }
                setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
                setOnClickListener {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/FalseEnvironment/JMAPJolt")))
                }
            })
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.addView(Button(this).apply {
            text = getString(R.string.about_close)
            isAllCaps = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * dp
                setColor(currentAccentColor.toColorInt())
            }
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dialog.dismiss() }
        })

        dialog.show()
    }

    private fun loadGeneralPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadImagesSwitch.isChecked = prefs.getBoolean("load_images", false)
        loadFaviconsSwitch.isChecked = prefs.getBoolean("load_favicons", false)
    }

    private fun saveGeneralPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean("load_images", loadImagesSwitch.isChecked)
            .putBoolean("load_favicons", loadFaviconsSwitch.isChecked)
            .apply()
    }

    private fun showSettingsMenuRoot() {
        settingsMenuContainer.visibility = View.VISIBLE
        settingsGeneralContainer.visibility = View.VISIBLE
        settingsSwipeContainer.visibility = View.GONE
        settingsUnifiedPushContainer.visibility = View.VISIBLE
        settingsThemeContainer.visibility = View.VISIBLE
        currentSettingsSection = SettingsSection.ROOT
        setDrawerIndicator(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        drawerToggle.syncState()
        applyNavIconTint(getOnAccentColor())
        invalidateOptionsMenu()
        updateTopBarState()
    }

    private fun bindDrawerNavigation() {
        navigationView.setNavigationItemSelectedListener { item ->
            if (item.itemId == R.id.nav_calendar) {
                showCalendarScreen()
            } else if (item.itemId == R.id.nav_settings) {
                showSettingsScreen()
            } else {
                selectedFolder = item.itemId
                if (composeContainer.visibility == View.VISIBLE) hideCompose()
                showMailboxScreen()
                applyFolderFilterAndRefresh()
                navigationView.post { rebuildDrawerMenu() }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun updateTopBarState() {
        val inMailbox = mailboxContainer.visibility == View.VISIBLE
        val title = when {
            settingsContainer.visibility == View.VISIBLE ->
                when (currentSettingsSection) {
                    SettingsSection.GENERAL -> getString(R.string.settings_general)
                    SettingsSection.SWIPE -> getString(R.string.settings_swipe_actions)
                    SettingsSection.UNIFIED_PUSH -> getString(R.string.settings_unifiedpush)
                    SettingsSection.THEME -> getString(R.string.settings_theme)
                    SettingsSection.ROOT -> getString(R.string.settings_title)
                }
            inMailbox -> getCurrentMailboxTitle()
            else -> getString(R.string.app_name)
        }
        supportActionBar?.title = title
        updateCustomTopBar(title, inMailbox = inMailbox)
    }

    private fun bindSettingsActions() {
        accentColorRow.setOnClickListener { showAccentColorDialog() }
        findViewById<LinearLayout>(R.id.settingsEditLabelsRow).setOnClickListener {
            showLabelEditorDialog()
        }
        settingsEditLabelsButton.apply {
            setOnClickListener { showLabelEditorDialog() }
        }

        unifiedPushSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            saveUnifiedPushEnabled(enabled)
            if (enabled) {
                registerUnifiedPushAuto("")
                sendUnifiedPushTestNotification()
            } else {
                UnifiedPush.unregisterApp(this, INSTANCE_DEFAULT)
                EmailSyncWorker.cancel(this)
                showThemedSnackbar(getString(R.string.settings_unifiedpush_disabled))
            }
        }

        sseSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            JmapEventSourceService.setEnabled(this, enabled)
            if (enabled && connectedAccount != null) {
                JmapEventSourceService.start(this)
            } else {
                JmapEventSourceService.stop(this)
            }
        }

    }

    private fun bindPullToRefresh() {
        mailSwipeRefresh.setOnChildScrollUpCallback { _, _ ->
            emailsRecyclerView.canScrollVertically(-1)
        }
        // Pull must travel further before triggering refresh: a slightly diagonal
        // swipe-to-delete/archive on the top rows would otherwise start a refresh.
        mailSwipeRefresh.setDistanceToTriggerSync(
            (PULL_TO_REFRESH_TRIGGER_DP * resources.displayMetrics.density).toInt()
        )
        mailSwipeRefresh.setOnRefreshListener {
            status.text = getString(R.string.mailbox_refreshing)
            refreshInboxNow { mailSwipeRefresh.isRefreshing = false }
        }
    }

    /**
     * Theme-aware snackbar with an optional action button that can show a leading icon.
     * Background and text colours follow the active theme; the action uses the accent colour.
     */
    internal fun showThemedSnackbar(
        message: String,
        actionLabel: String? = null,
        actionIcon: Int? = null,
        action: (() -> Unit)? = null
    ) {
        val hasAction = actionLabel != null && action != null
        val sb = Snackbar.make(
            drawerLayout, message,
            if (hasAction) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        )
        val dp = resources.displayMetrics.density
        val isLight = currentTheme == "light"
        val bg = if (isLight) "#FFFFFF".toColorInt() else "#2A2A2E".toColorInt()
        val fg = if (isLight) "#212121".toColorInt() else Color.WHITE
        val accent = currentAccentColor.toColorInt()
        sb.setTextColor(fg)
        sb.setActionTextColor(accent)
        sb.view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(bg)
        }
        if (hasAction) {
            sb.setAction(actionLabel) { action() }
            if (actionIcon != null) {
                sb.view.post {
                    val actionView = sb.view.findViewById<Button>(
                        com.google.android.material.R.id.snackbar_action
                    )
                    actionView?.let { btn ->
                        val d = ContextCompat.getDrawable(this, actionIcon)?.mutate()
                        d?.setTint(accent)
                        val size = (18 * dp).toInt()
                        d?.setBounds(0, 0, size, size)
                        btn.setCompoundDrawables(d, null, null, null)
                        btn.compoundDrawablePadding = (6 * dp).toInt()
                    }
                }
            }
        }
        sb.show()
    }

    internal fun attemptLeaveSettingsSubmenu() {
        if (currentSettingsSection == SettingsSection.ROOT) return
        showSettingsMenuRoot()
    }

    private fun refreshInboxNow(onDone: (() -> Unit)? = null) {
        val account = connectedAccount
        if (account == null) {
            Log.w(TAG, "refreshInboxNow: no connected account")
            status.text = getString(R.string.status_sync_not_connected)
            onDone?.invoke()
            return
        }
        Log.d(TAG, "refreshInboxNow: starting fetch")
        startPeriodicSync()
        mailSwipeRefresh.isRefreshing = false
        onDone?.invoke()
    }

    private fun registerUnifiedPushAuto(manualDistributor: String) {
        try {
            val distributors = UnifiedPush.getDistributors(this, arrayListOf())
            val preferred =
                    distributors.firstOrNull {
                        it.contains("ntfy", ignoreCase = true) ||
                                it.contains("sunup", ignoreCase = true)
                    }
                            ?: distributors.firstOrNull()

            val selected =
                    when {
                        manualDistributor.isNotBlank() && normalizeUnifiedPushLink(manualDistributor) == null ->
                                manualDistributor
                        !preferred.isNullOrBlank() -> preferred
                        else -> null
                    }

            if (!selected.isNullOrBlank()) {
                UnifiedPush.saveDistributor(this, selected)
            }

            UnifiedPush.registerApp(this, INSTANCE_DEFAULT, arrayListOf(), packageName)
            // Schedule the periodic fallback immediately so background sync works
            // even with no distributor installed or before an endpoint arrives.
            EmailSyncWorker.schedule(this)
            status.text = getString(R.string.settings_unifiedpush_registered, selected ?: "auto")
        } catch (_: Throwable) {
            status.text = getString(R.string.settings_unifiedpush_failed)
        }
    }

    /** Extension-visible wrapper (label helpers live in LabelHelper.kt). */
    internal fun rebuildDrawerMenuPublic() = rebuildDrawerMenu()

    private fun rebuildDrawerMenu() {
        val menu = navigationView.menu
        menu.clear()
        // Per-item icon colors (labels): disable the global tint and tint manually.
        navigationView.itemIconTintList = null
        // Theme-aware icon color: dark on light theme, light on dark themes. A hardcoded light
        // tint here previously turned drawer icons white after a rebuild on the light theme.
        val defaultIconTint = when (currentTheme) {
            "light" -> "#1B1B1F".toColorInt()
            else    -> "#E0E0E0".toColorInt()
        }

        var menuIndex = 0
        if (savedAccounts.size > 1) {
            val unifiedTitle: CharSequence = if (selectedFolder == R.id.nav_unified_inbox) {
                android.text.SpannableString("Unified Inbox").apply {
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                }
            } else {
                "Unified Inbox"
            }
            menu.add(0, R.id.nav_unified_inbox, menuIndex++, unifiedTitle)
                .setIcon(R.drawable.ic_lucide_inbox)
                .icon?.mutate()?.setTint(defaultIconTint)
        }

        categoryOrder.forEachIndexed { index, id ->
            val name = categoryNames[id] ?: getDefaultCategoryTitle(id)
            val title: CharSequence = if (id == selectedFolder) {
                android.text.SpannableString(name).apply {
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, name.length, 0)
                }
            } else {
                name
            }
            val item = menu.add(0, id, menuIndex + index, title)
            item.setIcon(getCategoryIcon(id))
            item.icon?.mutate()?.setTint(defaultIconTint)
            item.isCheckable = true
        }

        // User labels: colored tag icons, ordered; long-press drag reorders them.
        var orderIdx = menuIndex + categoryOrder.size
        val knownKeywords = labels.map { it.keyword }.toSet()
        labelNavIds.keys.retainAll { labelNavIds[it] in knownKeywords }
        labels.forEach { label ->
            val navId = labelNavIds.entries.find { it.value == label.keyword }?.key
                ?: View.generateViewId().also { labelNavIds[it] = label.keyword }
            val title: CharSequence = if (navId == selectedFolder) {
                android.text.SpannableString(label.name).apply {
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                }
            } else {
                label.name
            }
            val item = menu.add(0, navId, orderIdx++, title)
            item.setIcon(R.drawable.ic_lucide_tag)
            item.icon?.mutate()?.setTint(label.colorHex.toColorInt())
            item.isCheckable = true
        }

        val calendarEnabled = CalendarPrefs.isEnabled(this)
        val calendarItem = if (calendarEnabled) {
            menu.add(0, R.id.nav_calendar, orderIdx, getString(R.string.calendar_title)).apply {
                setIcon(R.drawable.ic_lucide_calendar)
                icon?.mutate()?.setTint(defaultIconTint)
                isCheckable = true
            }
        } else null

        val settingsItem =
                menu.add(
                        0,
                        R.id.nav_settings,
                        orderIdx + 1,
                        getString(R.string.settings_title)
                )
        settingsItem.setIcon(R.drawable.ic_lucide_settings)
        settingsItem.icon?.mutate()?.setTint(defaultIconTint)
        settingsItem.isCheckable = true

        // In the settings screen the accent highlight belongs on Settings, not the
        // previously selected folder (which stays remembered in selectedFolder).
        if (settingsContainer.visibility == View.VISIBLE) {
            settingsItem.isChecked = true
        } else if (calendarItem != null && calendarPanelView?.visibility == View.VISIBLE) {
            calendarItem.isChecked = true
        } else {
            menu.findItem(selectedFolder)?.isChecked = true
        }
        attachLabelDrag()

        val dp = resources.displayMetrics.density
        val accentInt = currentAccentColor.toColorInt()
        val r = android.graphics.Color.red(accentInt)
        val g = android.graphics.Color.green(accentInt)
        val b = android.graphics.Color.blue(accentInt)
        val cornerR = 999 * dp
        // Round only the trailing (right) corners; flat at the left screen edge -> tab/arrow shape.
        // Inset on the right so the accent bar does not run the full item width.
        val rightInset = (40 * dp).toInt()
        fun accentShape(alpha: Int): android.graphics.drawable.Drawable {
            val shape = android.graphics.drawable.GradientDrawable().apply {
                this.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(android.graphics.Color.argb(alpha, r, g, b))
                cornerRadii = floatArrayOf(0f, 0f, cornerR, cornerR, cornerR, cornerR, 0f, 0f)
            }
            return android.graphics.drawable.InsetDrawable(shape, 0, 0, rightInset, 0)
        }
        val stateList = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), accentShape(255))
            addState(intArrayOf(android.R.attr.state_activated), accentShape(255))
            addState(intArrayOf(android.R.attr.state_pressed), accentShape(110))
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        navigationView.post { navigationView.itemBackground = stateList }
    }

    private fun setupAdapters() {
        emailAdapter = EmailAdapter(this)
        val layoutManager = LinearLayoutManager(this)
        emailsRecyclerView.layoutManager = layoutManager
        emailsRecyclerView.adapter = emailAdapter
        attachMailSwipe()
        setupInfiniteScroll(layoutManager)
        setupSelectionBarListeners()
    }

    /**
     * Grows the page size when the user scrolls near the bottom, so more emails
     * load on demand instead of being capped at the first page. The periodic sync
     * loop refetches the folder with the larger [emailLimit].
     */
    private fun setupInfiniteScroll(layoutManager: LinearLayoutManager) {
        emailsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                // Only paginate when the current page is full — a short page means
                // we already have every email the folder holds.
                if (emails.size < emailLimit) return

                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= emails.size - LOAD_MORE_THRESHOLD) {
                    isLoadingMore = true
                    emailLimit += JMapClient.DEFAULT_EMAIL_LIMIT
                    refreshInboxNow()
                }
            }
        })
    }

    /** Long-press drag to reorder label rows inside the drawer's internal RecyclerView. */
    private fun attachLabelDrag() {
        if (labelDragHelper != null) return
        val rv = navigationView.getChildAt(0) as? RecyclerView ?: return

        fun itemIdOf(vh: RecyclerView.ViewHolder): Int? {
            val itemView = vh.itemView as? androidx.appcompat.view.menu.MenuView.ItemView ?: return null
            return (itemView.itemData as? MenuItem)?.itemId
        }

        val callback = object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = true
            override fun isItemViewSwipeEnabled() = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val id = itemIdOf(viewHolder) ?: return 0
                return if (labelNavIds.containsKey(id))
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                else 0
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val id = itemIdOf(target) ?: return false
                return labelNavIds.containsKey(id)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromKw = itemIdOf(viewHolder)?.let { labelNavIds[it] } ?: return false
                val toKw = itemIdOf(target)?.let { labelNavIds[it] } ?: return false
                val from = labels.indexOfFirst { it.keyword == fromKw }
                val to = labels.indexOfFirst { it.keyword == toKw }
                if (from < 0 || to < 0 || from == to) return false
                labels.add(to, labels.removeAt(from))
                // Don't save on every step – only on drop (clearView).
                recyclerView.adapter?.notifyItemMoved(
                    viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                )
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS
                    )
                    viewHolder?.itemView?.alpha = 0.7f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                // Persist new order and immediately re-sync the drawer menu (no post{}
                // so there is no visible delay between releasing the drag and the menu updating).
                saveLabels()
                rebuildDrawerMenu()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        labelDragHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(rv) }
    }

    private fun attachMailSwipe() {
        val callback =
                object :
                        ItemTouchHelper.SimpleCallback(
                                0,
                                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        ) {
                    private val paint = Paint()

                    override fun onMove(
                            rv: RecyclerView,
                            vh: RecyclerView.ViewHolder,
                            target: RecyclerView.ViewHolder
                    ) = false

                    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                        return 0.35f
                    }

                    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                        return Float.MAX_VALUE  // disabilita swipe da velocità — richiede rilascio dito
                    }

                    // While a row is being swiped horizontally, the pull-to-refresh
                    // spinner must not appear at all.
                    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                        super.onSelectedChanged(viewHolder, actionState)
                        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                            mailSwipeRefresh.isEnabled = false
                        }
                    }

                    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                        super.clearView(rv, vh)
                        mailSwipeRefresh.isEnabled = true
                    }

                    override fun onChildDraw(
                            c: Canvas,
                            rv: RecyclerView,
                            vh: RecyclerView.ViewHolder,
                            dX: Float,
                            dY: Float,
                            state: Int,
                            active: Boolean
                    ) {
                        val view = vh.itemView
                        val width = view.width.toFloat()
                        // Apply spring damping beyond 30% of item width
                        val maxSwipeDistance = width * 0.30f
                        val cappedDX = if (dX > 0) {
                            if (dX <= maxSwipeDistance) dX else maxSwipeDistance + (dX - maxSwipeDistance) * 0.2f
                        } else {
                            if (dX >= -maxSwipeDistance) dX else -maxSwipeDistance + (dX + maxSwipeDistance) * 0.2f
                        }

                        if (cappedDX != 0f) {
                            val action = if (cappedDX > 0) getRightSwipeAction() else getLeftSwipeAction()
                            val (colorRes, iconRes) = when (action) {
                                SwipeAction.DELETE -> Pair("#D32F2F".toColorInt(), R.drawable.ic_lucide_trash)
                                SwipeAction.ARCHIVE -> Pair("#388E3C".toColorInt(), R.drawable.ic_lucide_archive)
                                SwipeAction.MARK_READ -> Pair("#3D8BFD".toColorInt(), R.drawable.ic_lucide_eye)
                                SwipeAction.MARK_SPAM -> Pair("#F57C00".toColorInt(), R.drawable.ic_lucide_ban)
                            }
                            paint.color = colorRes

                            val itemHeight = view.bottom - view.top
                            val icon = ContextCompat.getDrawable(this@MainActivity, iconRes)?.mutate()
                            icon?.setTint(Color.WHITE)
                            val intrinsicWidth = icon?.intrinsicWidth ?: 0
                            val intrinsicHeight = icon?.intrinsicHeight ?: 0

                            // Clip everything to the revealed strip: the icon stays
                            // "behind" the row and is uncovered progressively instead
                            // of popping in/out at a pixel threshold.
                            val iconTop = view.top + (itemHeight - intrinsicHeight) / 2
                            val iconBottom = iconTop + intrinsicHeight
                            c.save()
                            if (cappedDX > 0) {
                                c.clipRect(
                                        view.left.toFloat(),
                                        view.top.toFloat(),
                                        view.left + cappedDX,
                                        view.bottom.toFloat()
                                )
                                c.drawColor(colorRes)
                                val iconLeft = view.left + 48
                                icon?.setBounds(iconLeft, iconTop, iconLeft + intrinsicWidth, iconBottom)
                            } else {
                                c.clipRect(
                                        view.right + cappedDX,
                                        view.top.toFloat(),
                                        view.right.toFloat(),
                                        view.bottom.toFloat()
                                )
                                c.drawColor(colorRes)
                                val iconRight = view.right - 48
                                icon?.setBounds(iconRight - intrinsicWidth, iconTop, iconRight, iconBottom)
                            }
                            icon?.draw(c)
                            c.restore()
                        }
                        super.onChildDraw(c, rv, vh, cappedDX, dY, state, active)
                    }

                    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                        val position = vh.adapterPosition
                        if (position !in emails.indices) return
                        val item = emails[position]
                        val action =
                                if (direction == ItemTouchHelper.RIGHT) getRightSwipeAction()
                                else getLeftSwipeAction()

                        val account = resolveAccountFor(item) ?: connectedAccount ?: return

                        // Drafts cannot be marked read or archived; cancel the swipe.
                        if (selectedFolder == R.id.nav_drafts &&
                            (action == SwipeAction.MARK_READ || action == SwipeAction.ARCHIVE)) {
                            emailAdapter.notifyItemChanged(position)
                            Snackbar.make(drawerLayout, "Not available for drafts", Snackbar.LENGTH_SHORT).show()
                            return
                        }

                        // Deleting from Trash is permanent: confirm first, restoring the row meanwhile.
                        if (selectedFolder == R.id.nav_trash && action == SwipeAction.DELETE) {
                            emailAdapter.notifyItemChanged(position)
                            confirmPermanentDelete(account, listOf(item.id))
                            return
                        }

                        // Archiving from the Favourites view keeps the email flagged, so it
                        // stays visible there (an email can be both favourited and archived).
                        // Snap the row back instead of removing it.
                        if (selectedFolder == R.id.nav_favourite && action == SwipeAction.ARCHIVE) {
                            emailAdapter.notifyItemChanged(position)
                            Snackbar.make(drawerLayout, "Archived", Snackbar.LENGTH_SHORT).show()
                            lifecycleScope.launch {
                                try {
                                    val archiveId = jmapClient.resolveMailboxIdByRole(account, "archive")
                                    if (archiveId != null) jmapClient.setMailbox(account, item.id, archiveId)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Archive from favourites failed", e)
                                }
                            }
                            return
                        }

                        // 1. Optimistic local UI update
                        when (action) {
                            SwipeAction.DELETE, SwipeAction.ARCHIVE, SwipeAction.MARK_SPAM -> {
                                emails.removeAt(position)
                                emailAdapter.notifyItemRemoved(position)
                                emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
                                emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
                                val targetNavId = when (action) {
                                    SwipeAction.DELETE -> R.id.nav_trash
                                    SwipeAction.ARCHIVE -> R.id.nav_archive
                                    else -> -1 // MARK_SPAM: removals only
                                }
                                updateFolderCachesForMove(item, targetNavId)
                                saveEmailCache()
                            }
                            SwipeAction.MARK_READ -> {
                                item.seen = !item.seen
                                emailAdapter.notifyItemChanged(position)
                                saveEmailCache()
                            }
                        }

                        // 2. Asynchronous JMAP server update
                        lifecycleScope.launch {
                            try {
                                when (action) {
                                    SwipeAction.DELETE -> {
                                        val trashId = jmapClient.resolveMailboxIdByRole(account, "trash")
                                        if (trashId != null) {
                                            jmapClient.setMailbox(account, item.id, trashId)
                                        }
                                    }
                                    SwipeAction.ARCHIVE -> {
                                        val archiveId = jmapClient.resolveMailboxIdByRole(account, "archive")
                                        if (archiveId != null) {
                                            jmapClient.setMailbox(account, item.id, archiveId)
                                        }
                                    }
                                    SwipeAction.MARK_READ -> {
                                        jmapClient.setSeen(account, item.id, item.seen)
                                    }
                                    SwipeAction.MARK_SPAM -> {
                                        val spamId = jmapClient.resolveMailboxIdByRole(account, "spam")
                                        if (spamId != null) {
                                            jmapClient.setMailbox(account, item.id, spamId)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to perform optimistic swipe action $action on server", e)
                            }
                        }
                    }
                }
        ItemTouchHelper(callback).attachToRecyclerView(emailsRecyclerView)
    }

    private fun moveCategory(from: Int, to: Int) {
        if (to !in categoryOrder.indices) return
        val item = categoryOrder.removeAt(from)
        categoryOrder.add(to, item)
    }

    private fun loadCategoryPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedOrder = prefs.getString(KEY_CATEGORY_ORDER, null)
        if (!savedOrder.isNullOrBlank()) {
            val parsed = savedOrder.split(",").mapNotNull { it.toIntOrNull() }
            if (parsed.size == categoryOrder.size && parsed.containsAll(categoryOrder)) {
                categoryOrder.clear()
                categoryOrder.addAll(parsed)
            }
        }
        categoryOrder.forEach { id ->
            val key = "category_name_$id"
            val saved = prefs.getString(key, null)
            if (!saved.isNullOrBlank()) {
                categoryNames[id] = saved
            } else {
                categoryNames[id] = getDefaultCategoryTitle(id)
            }
        }
    }

    private fun saveCategoryPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_CATEGORY_ORDER, categoryOrder.joinToString(","))
        categoryOrder.forEach { id -> editor.putString("category_name_$id", categoryNames[id]) }
        editor.putString(KEY_SWIPE_RIGHT_ACTION, getRightSwipeAction().name)
        editor.putString(KEY_SWIPE_LEFT_ACTION, getLeftSwipeAction().name)
        editor.apply()
    }

    private fun setupSwipeSpinners() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        swipeRightActionIdx = SwipeAction.valueOf(
            prefs.getString(KEY_SWIPE_RIGHT_ACTION, SwipeAction.DELETE.name) ?: SwipeAction.DELETE.name
        ).ordinal
        swipeLeftActionIdx = SwipeAction.valueOf(
            prefs.getString(KEY_SWIPE_LEFT_ACTION, SwipeAction.ARCHIVE.name) ?: SwipeAction.ARCHIVE.name
        ).ordinal

        val swipeOptions = SwipeAction.entries.map { labelForSwipeAction(it) }
        swipeRightDropdown.setOnClickListener {
            showSettingsDropdown(swipeRightDropdown, swipeOptions, swipeRightActionIdx) { idx ->
                swipeRightActionIdx = idx
                updateSettingsDropdownDisplays()
                saveSwipePreferences()
            }
        }
        swipeLeftDropdown.setOnClickListener {
            showSettingsDropdown(swipeLeftDropdown, swipeOptions, swipeLeftActionIdx) { idx ->
                swipeLeftActionIdx = idx
                updateSettingsDropdownDisplays()
                saveSwipePreferences()
            }
        }
    }

    private fun setupThemeSpinner() {
        val themeOptions = listOf(
            getString(R.string.settings_theme_gray),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_oled),
            getString(R.string.settings_theme_violet)
        )
        themeDropdown.setOnClickListener {
            showSettingsDropdown(themeDropdown, themeOptions, themeIdx) { idx ->
                themeIdx = idx
                val newTheme = when (idx) { 1 -> "light"; 2 -> "oled"; 3 -> "violet"; else -> "gray" }
                if (newTheme != currentTheme) {
                    currentTheme = newTheme
                    saveThemePreference()
                    applyTheme()
                }
            }
        }
    }

    private fun getRightSwipeAction(): SwipeAction = SwipeAction.entries[swipeRightActionIdx]
    private fun getLeftSwipeAction(): SwipeAction = SwipeAction.entries[swipeLeftActionIdx]

    internal fun updateSettingsDropdownDisplays() {
        val swipeLabels = SwipeAction.entries.map { labelForSwipeAction(it) }
        swipeLeftDropdownText.text = swipeLabels.getOrElse(swipeLeftActionIdx) { "" }
        swipeRightDropdownText.text = swipeLabels.getOrElse(swipeRightActionIdx) { "" }
        val themeLabels = listOf(
            getString(R.string.settings_theme_gray),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_oled),
            getString(R.string.settings_theme_violet)
        )
        themeDropdownText.text = themeLabels.getOrElse(themeIdx) { "" }
    }

    internal fun showSettingsDropdown(
        anchor: View,
        options: List<String>,
        currentIdx: Int,
        icons: List<Int>? = null,
        onSelected: (Int) -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val popupBg = getDialogBackgroundColor()
        val isLight = currentTheme == "light"
        val contentColor = if (isLight) "#212121".toColorInt() else Color.WHITE
        val selectedTint = if (isLight) 0x14000000 else 0x33FFFFFF

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * dp
                setColor(popupBg)
            }
            val vp = (6 * dp).toInt()
            setPadding(vp, vp, vp, vp)
            elevation = 8 * dp
        }

        var popupRef: android.widget.PopupWindow? = null

        options.forEachIndexed { idx, label ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val rowW = (208 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(rowW, (46 * dp).toInt()).also {
                    if (idx > 0) it.topMargin = (2 * dp).toInt()
                }
                val hp = (14 * dp).toInt()
                setPadding(hp, 0, hp, 0)
                // Selected row: rounded tonal pill with a check mark.
                if (idx == currentIdx) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 11 * dp
                        setColor(selectedTint)
                    }
                }
                icons?.getOrNull(idx)?.let { iconRes ->
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(contentColor)
                        val sz = (18 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                            it.marginEnd = (12 * dp).toInt()
                        }
                    })
                }
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(contentColor)
                    if (idx == currentIdx) typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (idx == currentIdx) {
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_lucide_check)
                        imageTintList = ColorStateList.valueOf(contentColor)
                        val sz = (18 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                            it.marginStart = (8 * dp).toInt()
                        }
                    })
                }
                setOnClickListener {
                    // Quick tap pulse, then dismiss and apply.
                    animateTap()
                    postDelayed({ popupRef?.dismiss(); onSelected(idx) }, 120)
                }
            })
        }

        val pw = android.widget.PopupWindow(
            container,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 10 * dp
            isOutsideTouchable = true
        }
        popupRef = pw
        pw.showAsDropDown(anchor, 0, (4 * dp).toInt())
        // Entrance: scale-in from the anchor corner with a fade (MD3 menu motion).
        container.alpha = 0f
        container.scaleX = 0.86f
        container.scaleY = 0.78f
        container.post {
            container.pivotX = container.width * 0.85f
            container.pivotY = 0f
            container.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
                .start()
        }
    }

    private fun saveSwipePreferences() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SWIPE_RIGHT_ACTION, getRightSwipeAction().name)
                .putString(KEY_SWIPE_LEFT_ACTION, getLeftSwipeAction().name)
                .apply()
    }

    private fun labelForSwipeAction(action: SwipeAction): String =
            when (action) {
                SwipeAction.DELETE -> getString(R.string.swipe_action_delete)
                SwipeAction.ARCHIVE -> getString(R.string.swipe_action_archive)
                SwipeAction.MARK_READ -> getString(R.string.swipe_action_read)
                SwipeAction.MARK_SPAM -> getString(R.string.swipe_action_spam)
            }

    private fun updateEmailsList(rawList: List<DisplayEmail>) {
        // A fetch landed: allow the next scroll-triggered page load.
        isLoadingMore = false
        // Stable adapter ids derive from email ids: a duplicate id in the list
        // (e.g. multi-account label sync merging overlapping results) crashes
        // RecyclerView with "Called attach on a child which is not detached".
        val newList = rawList.distinctBy { it.id }
        val folderChanged = prevUpdateFolder != selectedFolder
        prevUpdateFolder = selectedFolder

        val diffResult = if (!folderChanged) {
            androidx.recyclerview.widget.DiffUtil.calculateDiff(
                    object : androidx.recyclerview.widget.DiffUtil.Callback() {
                        override fun getOldListSize(): Int = emails.size
                        override fun getNewListSize(): Int = newList.size
                        override fun areItemsTheSame(
                                oldItemPosition: Int,
                                newItemPosition: Int
                        ): Boolean {
                            return emails[oldItemPosition].id == newList[newItemPosition].id
                        }
                        override fun areContentsTheSame(
                                oldItemPosition: Int,
                                newItemPosition: Int
                        ): Boolean {
                            val a = emails[oldItemPosition]
                            val b = newList[newItemPosition]
                            return a.seen == b.seen &&
                                    a.isFavorite == b.isFavorite &&
                                    a.labels == b.labels &&
                                    a.preview == b.preview &&
                                    a.subject == b.subject &&
                                    a.from == b.from
                        }
                    }
            )
        } else null

        val firstChanged = emails.firstOrNull()?.id != newList.firstOrNull()?.id
        baseEmails.clear()
        baseEmails.addAll(newList)
        emails.clear()
        emails.addAll(newList)
        if (diffResult != null) diffResult.dispatchUpdatesTo(emailAdapter)
        else emailAdapter.notifyDataSetChanged()

        if (firstChanged && !isSearchActive) {
            emailsRecyclerView.post { emailsRecyclerView.scrollToPosition(0) }
        }

        saveEmailCache()

        emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
        emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE

        if (pendingMailboxShow) {
            pendingMailboxShow = false
            showMailboxScreen(skipRefresh = true)
            loadingOverlay.animate()
                .alpha(0f)
                .setDuration(350)
                .withEndAction {
                    loadingOverlay.visibility = View.GONE
                    loadingOverlay.alpha = 1f
                }
                .start()
        }

        tryOpenPendingWidgetEmail()
    }

    private fun applyFolderFilterAndRefresh() {
        // New folder starts at the first page again.
        emailLimit = JMapClient.DEFAULT_EMAIL_LIMIT
        isLoadingMore = false
        val folderTitle = getCurrentMailboxTitle()
        supportActionBar?.title = folderTitle
        updateCustomTopBar(folderTitle, inMailbox = true)

        val cached = folderCache[selectedFolder]
        if (cached != null) {
            updateEmailsList(cached)
        } else {
            emails.clear()
            emailAdapter.notifyDataSetChanged()
            emptyStateView.visibility = View.GONE
            emailsRecyclerView.visibility = View.GONE
            // Show the persisted offline snapshot immediately (works with no network);
            // the periodic sync below refreshes it once the network responds.
            loadOfflineCache(selectedFolder)
        }

        startPeriodicSync()
    }

    /** Cache bucket for a folder, scoped per account (or "unified" for the merged inbox). */
    private fun cacheBucket(folderId: Int): String? {
        val scope = if (folderId == R.id.nav_unified_inbox) "unified"
            else connectedAccount?.email ?: return null
        return com.falseenvironment.jmapjolt.cache.EmailCacheStore.bucket(scope, folderId)
    }

    /** Display the persisted snapshot for a folder before the network responds. */
    private fun loadOfflineCache(folderId: Int) {
        val bucket = cacheBucket(folderId) ?: return
        lifecycleScope.launch {
            val cached = runCatching {
                com.falseenvironment.jmapjolt.cache.EmailCacheStore.load(this@MainActivity, bucket)
            }.getOrDefault(emptyList())
            // Skip if the user already switched folders or the network beat us to it.
            if (cached.isEmpty() || selectedFolder != folderId || emails.isNotEmpty()) return@launch
            folderCache[folderId] = cached
            updateEmailsList(cached)
        }
    }

    /** Persist a freshly fetched folder snapshot for offline viewing. */
    private fun persistOfflineCache(folderId: Int, list: List<DisplayEmail>) {
        val bucket = cacheBucket(folderId) ?: return
        lifecycleScope.launch {
            runCatching {
                com.falseenvironment.jmapjolt.cache.EmailCacheStore.save(this@MainActivity, bucket, list)
            }
        }
    }

    private fun getFolderRole(navId: Int): String? =
            when (navId) {
                R.id.nav_sent -> "sent"
                R.id.nav_drafts -> "drafts"
                R.id.nav_spam -> "junk"
                R.id.nav_trash -> "trash"
                R.id.nav_archive -> "archive"
                else -> null
            }

    internal fun toggleSelection(id: String) {
        if (selectedEmails.contains(id)) {
            selectedEmails.remove(id)
        } else {
            selectedEmails.add(id)
        }
        updateSelectionBar()
        val pos = emails.indexOfFirst { it.id == id }
        if (pos >= 0) emailAdapter.notifyItemChanged(pos) else emailAdapter.notifyDataSetChanged()
    }

    private fun updateSelectionBar() {
        if (selectedEmails.isEmpty()) {
            searchBarContainer.visibility = View.VISIBLE
            selectionBarContainer.visibility = View.GONE
        } else {
            searchBarContainer.visibility = View.GONE
            selectionBarContainer.visibility = View.VISIBLE
            selectionCountText.text = "${selectedEmails.size} selected"
            val allSeen = selectedEmails.all { id -> emails.find { it.id == id }?.seen == true }
            selectionReadBtn.contentDescription = if (allSeen) "Mark Unread" else "Mark Read"
            // In Archive the action button restores the email to the Inbox instead.
            if (selectedFolder == R.id.nav_archive) {
                selectionArchiveBtn.setImageResource(R.drawable.ic_lucide_archive_restore)
                selectionArchiveBtn.contentDescription = "Move to Inbox"
            } else {
                selectionArchiveBtn.setImageResource(R.drawable.ic_lucide_archive)
                selectionArchiveBtn.contentDescription = "Archive"
            }
        }
    }

    private fun setupSelectionBarListeners() {
        selectionCloseBtn.setOnClickListener { clearSelection() }
        selectionArchiveBtn.setOnClickListener {
            performAction(if (selectedFolder == R.id.nav_archive) "unarchive" else "archive")
        }
        selectionDeleteBtn.setOnClickListener { performAction("delete") }
        selectionReadBtn.setOnClickListener { performAction("toggleRead") }
        selectionMoreBtn.setOnClickListener { performAction("more") }

        searchBarMenuIcon.setOnClickListener {
            if (drawerToggle.isDrawerIndicatorEnabled) drawerLayout.openDrawer(GravityCompat.START)
            else handleNavigationClick()
        }

        searchBarTitle.setOnClickListener { activateSearch() }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                searchClearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                applySearchFilter(query)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        searchClearBtn.setOnClickListener { deactivateSearch() }
    }

    private fun activateSearch() {
        // If an email is open, leave it first so the search results are actually visible.
        if (isShowingEmailDetail) closeEmailDetail()
        isSearchActive = true
        searchBarTitle.visibility = View.GONE
        searchInput.visibility = View.VISIBLE
        searchClearBtn.visibility = View.GONE
        // Scope chips: default to the current folder when it maps to a chip, else All.
        searchScope = searchScopes.firstOrNull { it.second == selectedFolder }?.second
        refreshSearchChips()
        searchChipsScroll.animate().cancel()
        searchChipsScroll.layoutParams = searchChipsScroll.layoutParams.also {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        searchChipsScroll.visibility = View.VISIBLE
        searchChipsScroll.alpha = 0f
        searchChipsScroll.translationY = -40f * resources.displayMetrics.density
        searchChipsScroll.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
        searchInput.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun deactivateSearch() {
        isSearchActive = false
        searchInput.text.clear()
        searchInput.visibility = View.GONE
        searchBarTitle.visibility = View.VISIBLE
        searchClearBtn.visibility = View.GONE
        animateChipsBarOut()
        searchScope = null
        hideKeyboard()
        emails.clear()
        emails.addAll(baseEmails)
        emailAdapter.notifyDataSetChanged()
        emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
        emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Collapses the scope chips bar height + fades it, so it retracts up behind the top bar. */
    private fun animateChipsBarOut() {
        val bar = searchChipsScroll
        bar.animate().cancel()
        val startH = bar.height
        if (bar.visibility != View.VISIBLE || startH == 0) {
            bar.visibility = View.GONE
            return
        }
        val anim = android.animation.ValueAnimator.ofInt(startH, 0).setDuration(200)
        anim.interpolator = android.view.animation.AccelerateInterpolator()
        anim.addUpdateListener { va ->
            val h = va.animatedValue as Int
            bar.layoutParams = bar.layoutParams.also { it.height = h }
            bar.alpha = h.toFloat() / startH
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                bar.visibility = View.GONE
                bar.alpha = 1f
                bar.layoutParams = bar.layoutParams.also {
                    it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        })
        anim.start()
    }

    // Search scope chips: label -> drawer folder id (null = search everywhere).
    private val searchScopes = listOf<Pair<String, Int?>>(
        "All" to null,
        "Inbox" to R.id.nav_inbox,
        "Favorite" to R.id.nav_favourite,
        "Archive" to R.id.nav_archive,
        "Sent" to R.id.nav_sent,
        "Trash" to R.id.nav_trash
    )

    private fun refreshSearchChips() {
        val dp = resources.displayMetrics.density
        searchChipsRow.removeAllViews()
        val accent = currentAccentColor.toColorInt()
        val isLight = currentTheme == "light"
        val tonalBg = if (isLight) "#E8E8EC".toColorInt() else "#2A2A2A".toColorInt()
        val tonalText = if (isLight) "#1A1A1A".toColorInt() else "#EBEBF0".toColorInt()
        searchScopes.forEach { (label, scope) ->
            val selected = scope == searchScope
            searchChipsRow.addView(TextView(this).apply {
                text = label
                textSize = 13f
                typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD
                           else android.graphics.Typeface.DEFAULT
                setTextColor(if (selected) getOnAccentColor() else tonalText)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10 * dp
                    setColor(if (selected) accent else tonalBg)
                }
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = (8 * dp).toInt() }
                setOnClickListener {
                    if (searchScope != scope) {
                        searchScope = scope
                        refreshSearchChips()
                        applySearchFilter(searchInput.text?.toString() ?: "")
                    }
                }
            })
        }
    }

    /** Emails to search through, based on the selected scope chip. */
    private fun searchSourceEmails(): List<DisplayEmail> {
        val scope = searchScope
        return when {
            scope == null -> {
                // All: union of every cached folder plus the current list, newest first.
                val seen = HashSet<String>()
                (folderCache.values.flatten() + baseEmails)
                    .filter { seen.add(it.id) }
                    .sortedByDescending { it.receivedAt }
            }
            scope == selectedFolder -> baseEmails.toList()
            else -> folderCache[scope] ?: emptyList()
        }
    }

    private fun applySearchFilter(query: String) {
        val source = if (isSearchActive) searchSourceEmails() else baseEmails.toList()
        val filtered = if (query.isBlank()) source else source.filter {
            it.subject.contains(query, ignoreCase = true) ||
            it.from.contains(query, ignoreCase = true) ||
            it.preview.contains(query, ignoreCase = true) ||
            labelsOf(it).any { label -> label.name.contains(query, ignoreCase = true) }
        }
        emails.clear()
        emails.addAll(filtered.distinctBy { it.id })
        emailAdapter.notifyDataSetChanged()
        emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
        emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearSelection() {
        val positions = selectedEmails.mapNotNull { id ->
            emails.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        selectedEmails.clear()
        updateSelectionBar()
        positions.forEach { emailAdapter.notifyItemChanged(it) }
    }

    /**
     * Removes the given emails from the visible list (and the search base list) with a
     * per-row removal animation. Call any clearSelection()/ActionMode.finish() BEFORE this,
     * since those trigger a full notifyDataSetChanged that would cancel the animation.
     */
    private fun removeEmailsAnimated(ids: Collection<String>) {
        val idSet = ids.toSet()
        for (i in emails.indices.reversed()) {
            if (emails[i].id in idSet) {
                emails.removeAt(i)
                emailAdapter.notifyItemRemoved(i)
            }
        }
        baseEmails.removeAll { it.id in idSet }
        emptyStateView.visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
        emailsRecyclerView.visibility = if (emails.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Overlays pending favorite toggles on freshly synced data until the server reflects them. */
    private fun applyOptimisticFavorite(
        list: List<DisplayEmail>,
        isFavFolder: Boolean
    ): List<DisplayEmail> {
        if (optimisticFavorite.isEmpty()) return list
        // Drop overrides the server has already caught up with.
        list.forEach { e -> if (optimisticFavorite[e.id] == e.isFavorite) optimisticFavorite.remove(e.id) }
        if (isFavFolder) {
            val idsInList = list.map { it.id }.toSet()
            optimisticFavorite.entries.removeAll { (id, fav) -> !fav && id !in idsInList }
        }
        if (optimisticFavorite.isEmpty()) return list
        var result = list.map { e ->
            val ov = optimisticFavorite[e.id]
            if (ov != null && ov != e.isFavorite) e.copy(isFavorite = ov) else e
        }
        if (isFavFolder) result = result.filter { optimisticFavorite[it.id] != false }
        return result
    }

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
    private fun confirmPermanentDelete(account: JMapClient.ConnectedAccount, ids: List<String>) {
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

    private fun performAction(action: String) {
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

    private suspend fun resolveMailboxIdByRole(
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
    private suspend fun resolveOrCreateArchive(account: JMapClient.ConnectedAccount): String? {
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

    private fun startPeriodicSync() {
        syncJob?.cancel()
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
                                        getString(R.string.status_sync_contacting, account.apiUrl)
                            }

                            if (isUnifiedInbox) {
                                val allAccounts = BackgroundEmailSyncReceiver.readAllAccounts(this@MainActivity)
                                val merged = allAccounts.flatMap { acc ->
                                    try {
                                        jmapClient.fetchEmails(acc, limit = emailLimit).map { e ->
                                            DisplayEmail(
                                                e.id, e.subject, e.from, e.fromEmail,
                                                e.preview, e.fullBody, e.seen, e.isStarred,
                                                e.receivedAt, e.toEmail,
                                                attachments = e.attachments,
                                                accountEmail = acc.email,
                                                labels = e.keywords.toList()
                                            )
                                        }
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
                                    getString(R.string.status_sync_ok_empty, folderTitle)
                                else getString(R.string.status_sync_ok, merged.size)
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
                                                labels = it.keywords.toList()
                                        )
                                    }
                            val mergedList = applyOptimisticFavorite(newEmailsList, isFav)
                            folderCache[currentFolderId] = mergedList
                            updateEmailsList(mergedList)
                            persistOfflineCache(currentFolderId, mergedList)

                            status.text =
                                    if (fresh.isEmpty())
                                            getString(R.string.status_sync_ok_empty, folderTitle)
                                    else getString(R.string.status_sync_ok, fresh.size)
                        } catch (_: CancellationException) {
                            return@launch
                        } catch (e: Throwable) {
                            Log.e(TAG, "Sync failed", e)
                            status.text = getString(R.string.status_sync_failed, e.message ?: "-")
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

    private fun saveUnifiedPushEnabled(enabled: Boolean) {
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

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun persistConnectedAccount(account: JMapClient.ConnectedAccount, serverUrl: String) {
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

    private fun saveAccounts() {
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

    private fun switchToSavedAccount(account: AccountEntry, forceInbox: Boolean = false) {
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
        status.text = "Fetching new emails..."
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

    private fun getDefaultCategoryTitle(id: Int): String {
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

    private fun getCategoryIcon(id: Int): Int {
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
    }

    private fun handleCalendarIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CALENDAR, false) == true) {
            intent.removeExtra(EXTRA_OPEN_CALENDAR)
            showCalendarScreen()
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_DRAWER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_DRAWER)
            drawerLayout.post { drawerLayout.openDrawer(GravityCompat.START) }
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
        internal const val PREFS_NAME = "mail_prefs"
        internal const val EXTRA_OPEN_CALENDAR = "open_calendar"
        internal const val EXTRA_OPEN_DRAWER = "open_drawer"

        // Pull-to-refresh trigger distance (default is ~64dp; raised to avoid
        // accidental refreshes while swiping the top email row horizontally).
        private const val PULL_TO_REFRESH_TRIGGER_DP = 160
        // Trigger the next page when within this many rows of the bottom.
        private const val LOAD_MORE_THRESHOLD = 10

        // Detects common HTML elements so HTML fragments (no <html> root) are rendered as
        // markup instead of being escaped and shown as raw text.
        private val HTML_MARKUP_REGEX = Regex(
            "</?(?:p|div|br|span|a|table|tr|td|th|tbody|thead|ul|ol|li|blockquote|" +
                "h[1-6]|strong|em|b|i|u|img|pre|code|font|hr|center|dl|dt|dd|figure|" +
                "article|section|head|body|html)\\b",
            RegexOption.IGNORE_CASE
        )

        private const val KEY_WELCOME_SHOWN = "welcome_shown"
        private const val KEY_CATEGORY_ORDER = "category_order"
        private const val KEY_UP_ENABLED = "up_enabled"
        private const val KEY_UP_MANUAL_DISTRIBUTOR = "up_manual_distributor"
        private const val KEY_UP_AUTO_TOPIC = "up_auto_topic"
        private const val KEY_LAST_UP_ENDPOINT = "last_up_endpoint"
        private const val KEY_SWIPE_RIGHT_ACTION = "swipe_right_action"
        private const val KEY_SWIPE_LEFT_ACTION = "swipe_left_action"
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

    private fun showLinkConfirmationDialog(url: String) {
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

    private fun showMoveLabelPicker(
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

    private fun fetchAllFoldersBackground() {
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
