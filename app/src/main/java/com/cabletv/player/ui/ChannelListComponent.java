package com.cabletv.player.ui;

// 配色说明：本文件里出现的颜色值全部来自 LiteTV 原有调色板（见 res/values/colors.xml）——
// 主色 colorAccent(#FF6B35) 与其暗调 item_selected_dim_text(#FF9966)、正文 text_primary/FFFFFF、
// 次要 text_secondary/CCCCCC、节目单 epg_text_color/888888、next_program_text/999999。
// 没有引入新的色相，改色只需改 colors.xml。

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.config.ChannelNavigator;
import com.cabletv.player.epg.EpgCache;
import com.cabletv.player.epg.EpgManager;
import com.cabletv.player.model.Channel;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 分段式频道选择页（电视友好版）。
 *
 * <p>整屏三段布局，和 mytv 一致：左侧是分组栏，右侧是频道网格，底部是所选频道的节目单。
 * 遥控器操作：
 * <ul>
 *   <li>上下：网格内逐行移动；在最后一行继续按下 → 进入底部节目单；在分组栏里切换分组</li>
 *   <li>左右：网格内逐格移动；在第一列按左 → 进入分组栏；节目单里左右切换节目（到头翻日期）</li>
 *   <li>OK：确认频道 / 回看所选节目 / 在分组栏上回到网格</li>
 *   <li>长按 OK：收藏 / 取消收藏（节目单里则是回看）</li>
 *   <li>BACK：从分组栏、节目单折回网格；再按一次关闭</li>
 * </ul>
 *
 * <p>行本身永远不可获得 Android 焦点：光标由 MainActivity 的按键分发统一驱动，
 * 三块区域不可能对"光标在哪"产生分歧（这也是原实现的设计，保留）。
 */
public class ChannelListComponent extends FrameLayout implements IControlComponent {
    private static final String TAG = "ChannelListComponent";

    /** 分组栏宽度；再窄分组名就放不下了。 */
    private static final int RAIL_WIDTH_DP = 200;
    /** 一张频道卡片至少要有这么宽，用来算列数。 */
    private static final int MIN_CARD_WIDTH_DP = 168;
    private static final int CARD_HEIGHT_DP = 76;
    private static final int RAIL_ROW_HEIGHT_DP = 52;
    private static final int PROGRAM_ITEM_HEIGHT_DP = 66;
    private static final int PROGRAM_ITEM_WIDTH_DP = 210;
    private static final int MAX_DAY_BACK = 6;   // 今天 + 往前 6 天 = 7 天窗口
    private static final int MAX_DAY_AHEAD = 1;  // 上游节目单通常带明天

    private final ChannelNavigator mNavigator;
    private final RowLabelScroller mCardScroller = new RowLabelScroller();
    private final RowLabelScroller mProgramScroller = new RowLabelScroller();

    private View mScrim;
    private LinearLayout mRailSection;
    private LinearLayout mGridSection;
    private LinearLayout mProgramPanel;
    private RecyclerView mGroupRecyclerView;
    private RecyclerView mChannelRecyclerView;
    private RecyclerView mProgramRecyclerView;
    private TextView mGroupTitleView;
    private TextView mGridTitleView;
    private TextView mProgramTitleView;
    private TextView mProgramSubtitleView;
    private TextView mEmptyView;

    private GroupListAdapter mGroupAdapter;
    private ChannelCardAdapter mCardAdapter;
    private ProgramListAdapter mProgramAdapter;

    /** 当前浏览分组里的频道，以及每张卡片对应的播放列表扁平下标。 */
    private List<Channel> mChannels = new ArrayList<>();
    private List<Integer> mVisibleIndices = new ArrayList<>();
    private int mBrowsedGroup = ChannelNavigator.FAVORITES_GROUP;
    private int mCurrentChannelIndex = 0;
    private int mSelectedChannelIndex = 0;
    private int mGridPosition = 0;
    private int mSpanCount = 4;

    private int mSelectedProgramIndex = 0;
    private int mDayOffset = 0;
    private long mProgramFocusTimeMs = 0;
    private List<Integer> mAvailableDayOffsets = new ArrayList<>();
    private boolean mProgramListVisible = false;

    private EpgManager mEpgManager;
    private OnChannelSelectedListener mSelectionListener;
    private OnProgramClickListener mProgramClickListener;

    public enum FocusPanel { GROUPS, CHANNELS, PROGRAMS }

    private FocusPanel mFocusPanel = FocusPanel.CHANNELS;

    public interface OnChannelSelectedListener {
        void onChannelSelected(int index, Channel channel);
    }

    /** 回看：用户点了某个节目。 */
    public interface OnProgramClickListener {
        void onProgramClick(Channel channel, EpgManager.Program program);
    }

    public ChannelListComponent(@NonNull Context context, ChannelNavigator navigator) {
        super(context);
        mNavigator = navigator;
        initView(context);
        mGroupAdapter = new GroupListAdapter(context, navigator);
        mGroupRecyclerView.setAdapter(mGroupAdapter);
        mCardAdapter = new ChannelCardAdapter(context, navigator, mCardScroller);
        mChannelRecyclerView.setAdapter(mCardAdapter);
        mGroupAdapter.setRowClickListener(this::onGroupRowTap);
        mCardAdapter.setRowClickListener(this::onCardTap);
    }

    // ------------------------------------------------------------------ 布局

    private void initView(Context context) {
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mScrim = new View(context);
        mScrim.setBackgroundResource(R.drawable.bg_picker_scrim);
        addView(mScrim, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp2px(40), dp2px(24), dp2px(40), dp2px(20));
        addView(page, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 顶部：标题 / 当前分组 / 操作提示
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp2px(52)));
        page.addView(header);

        TextView title = new TextView(context);
        title.setText(R.string.picker_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        mGroupTitleView = new TextView(context);
        mGroupTitleView.setTextColor(0xFF999999);
        mGroupTitleView.setTextSize(14);
        mGroupTitleView.setSingleLine();
        mGroupTitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams groupTitleLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        groupTitleLp.leftMargin = dp2px(14);
        header.addView(mGroupTitleView, groupTitleLp);

        TextView hint = new TextView(context);
        hint.setText(R.string.picker_hint);
        hint.setTextColor(0xFF888888);
        hint.setTextSize(12);
        header.addView(hint);

        // 主体：左分组栏 + 右频道网格
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(body);

        mRailSection = new LinearLayout(context);
        mRailSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(
                dp2px(RAIL_WIDTH_DP), LayoutParams.MATCH_PARENT);
        railLp.rightMargin = dp2px(20);
        body.addView(mRailSection, railLp);

        TextView railTitle = new TextView(context);
        railTitle.setText(R.string.groups);
        railTitle.setTextColor(0xFF888888);
        railTitle.setTextSize(12);
        railTitle.setPadding(dp2px(10), 0, 0, dp2px(8));
        mRailSection.addView(railTitle);

        mGroupRecyclerView = new RecyclerView(context);
        mGroupRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        mGroupRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        mGroupRecyclerView.setBackgroundColor(0x00000000);
        mGroupRecyclerView.setFocusable(false);
        mGroupRecyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
        mRailSection.addView(mGroupRecyclerView);

        mGridSection = new LinearLayout(context);
        mGridSection.setOrientation(LinearLayout.VERTICAL);
        mGridSection.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        body.addView(mGridSection);

        mGridTitleView = new TextView(context);
        mGridTitleView.setTextColor(0xFF888888);
        mGridTitleView.setTextSize(12);
        mGridTitleView.setPadding(dp2px(10), 0, 0, dp2px(8));
        mGridSection.addView(mGridTitleView);

        mChannelRecyclerView = new RecyclerView(context);
        mChannelRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        mChannelRecyclerView.setLayoutManager(new GridLayoutManager(context, mSpanCount));
        mChannelRecyclerView.setBackgroundColor(0x00000000);
        mChannelRecyclerView.setFocusable(false);
        mChannelRecyclerView.setClipToPadding(false);
        mChannelRecyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
        mChannelRecyclerView.setPadding(0, dp2px(2), 0, dp2px(2));
        mGridSection.addView(mChannelRecyclerView);

        mEmptyView = new TextView(context);
        mEmptyView.setTextColor(0xFF888888);
        mEmptyView.setTextSize(14);
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setVisibility(GONE);
        mGridSection.addView(mEmptyView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部：所选频道的节目单
        mProgramPanel = new LinearLayout(context);
        mProgramPanel.setOrientation(LinearLayout.VERTICAL);
        mProgramPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp2px(PROGRAM_ITEM_HEIGHT_DP + 46)));
        page.addView(mProgramPanel);

        LinearLayout programHeader = new LinearLayout(context);
        programHeader.setOrientation(LinearLayout.HORIZONTAL);
        programHeader.setGravity(Gravity.CENTER_VERTICAL);
        programHeader.setPadding(dp2px(4), dp2px(6), dp2px(4), dp2px(6));
        mProgramPanel.addView(programHeader);

        mProgramTitleView = new TextView(context);
        mProgramTitleView.setText(R.string.todays_programs);
        mProgramTitleView.setTextColor(0xFFFFFFFF);
        mProgramTitleView.setTextSize(14);
        mProgramTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        programHeader.addView(mProgramTitleView);

        mProgramSubtitleView = new TextView(context);
        mProgramSubtitleView.setTextColor(0xFF888888);
        mProgramSubtitleView.setTextSize(12);
        mProgramSubtitleView.setSingleLine();
        mProgramSubtitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        subLp.leftMargin = dp2px(12);
        programHeader.addView(mProgramSubtitleView, subLp);

        mProgramRecyclerView = new RecyclerView(context);
        mProgramRecyclerView.setLayoutManager(
                new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        mProgramRecyclerView.setBackgroundColor(0x00000000);
        mProgramRecyclerView.setFocusable(false);
        mProgramRecyclerView.setClipToPadding(false);
        mProgramRecyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
        mProgramRecyclerView.setPadding(0, 0, 0, dp2px(2));
        mProgramPanel.addView(mProgramRecyclerView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp2px(PROGRAM_ITEM_HEIGHT_DP)));

        applyOpacity();
        applyFocusChrome();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 列数按可用宽度算：1080p 一般是 5 列，720p 是 4 列，都不会挤成一条条。
        int available = w - dp2px(80) - dp2px(RAIL_WIDTH_DP + 20);
        int span = Math.max(3, available / dp2px(MIN_CARD_WIDTH_DP));
        if (span != mSpanCount) {
            mSpanCount = span;
            mChannelRecyclerView.setLayoutManager(new GridLayoutManager(getContext(), span));
            mChannelRecyclerView.setAdapter(mCardAdapter);
            scrollGridToSelection();
        }
    }

    /** 设置里选的整体不透明度：深色底越实，卡片上的字越清楚。 */
    public void applyOpacity() {
        int level = AppConfig.getSidebarOpacity();
        float alpha;
        switch (level) {
            case AppConfig.SIDEBAR_OPAQUE:
                alpha = 1.0f;
                break;
            case AppConfig.SIDEBAR_SEMI_OPAQUE:
                alpha = 0.94f;
                break;
            default:
                alpha = 0.86f;
                break;
        }
        if (mScrim != null) {
            mScrim.setAlpha(alpha);
        }
    }

    public void setEpgManager(EpgManager epgManager) {
        mEpgManager = epgManager;
        if (mEpgManager != null) {
            mEpgManager.addOnEpgUpdatedListener(() -> {
                Log.d(TAG, "EPG updated, refreshing guide strip");
                refreshProgramStrip();
            });
        }
    }

    public void setOnChannelSelectedListener(OnChannelSelectedListener listener) {
        mSelectionListener = listener;
    }

    public void setOnProgramClickListener(OnProgramClickListener listener) {
        mProgramClickListener = listener;
    }

    // ------------------------------------------------------------------ 数据

    /** 播放列表变化后重建两块列表。 */
    public void refreshChannels(int currentChannelIndex) {
        mCurrentChannelIndex = currentChannelIndex;
        mGroupAdapter.notifyDataSetChanged();
        showGroup(mNavigator.currentGroupIndex(), currentChannelIndex);
    }

    /**
     * 列出一个分组，并高亮其中的某个频道。浏览分组只是"预览"，光标确认之前不会换台。
     *
     * @param channelIndexToSelect 扁平下标；不在该分组里时落在第一张卡片
     */
    public void showGroup(int groupIndex, int channelIndexToSelect) {
        ChannelNavigator.Group group = mNavigator.group(groupIndex);
        if (group == null) {
            groupIndex = ChannelNavigator.ALL_GROUP;
            group = mNavigator.group(groupIndex);
            if (group == null) {
                return;
            }
        }
        mBrowsedGroup = groupIndex;
        mChannels = mNavigator.channelsOf(groupIndex);
        mVisibleIndices = new ArrayList<>(group.channels);

        String label = groupLabel(getContext(), group);
        mGroupTitleView.setText(label);
        mGridTitleView.setText(getContext().getString(R.string.picker_group_count,
                label, mChannels.size()));
        mGroupAdapter.setSelectedGroup(groupIndex);
        scrollRailTo(groupIndex);

        boolean empty = mChannels.isEmpty();
        int position = mVisibleIndices.indexOf(channelIndexToSelect);
        if (position < 0) {
            position = 0;
        }
        mSelectedChannelIndex = empty ? -1 : mVisibleIndices.get(position);
        mGridPosition = empty ? 0 : position;

        mCardAdapter.updateData(mChannels, mVisibleIndices,
                mVisibleIndices.indexOf(mCurrentChannelIndex), mGridPosition,
                mFocusPanel == FocusPanel.CHANNELS);
        mEmptyView.setText(group.favorites ? R.string.favorites_empty : R.string.group_empty);
        mEmptyView.setVisibility(empty ? VISIBLE : GONE);
        mChannelRecyclerView.setVisibility(empty ? GONE : VISIBLE);
        if (empty) {
            hideProgramStrip();
        } else {
            scrollGridToSelection();
            refreshProgramStrip();
        }
        updateGridTitleForFocus();
    }

    /** 分组栏里上下移动（到头折返）。 */
    public void moveGroupSelection(boolean down) {
        int count = mNavigator.groupCount();
        if (count == 0) {
            return;
        }
        int next = ((mBrowsedGroup + (down ? 1 : -1)) % count + count) % count;
        if (next == mBrowsedGroup) {
            return;
        }
        showGroup(next, mCurrentChannelIndex);
    }

    /** 频道网格里上下移动一行。 */
    public void moveChannelSelection(boolean down) {
        if (mChannels.isEmpty()) {
            return;
        }
        int next = mGridPosition + (down ? mSpanCount : -mSpanCount);
        if (next < 0) {
            return;
        }
        if (next >= mChannels.size()) {
            // 已经到最后一行：往下交给节目单（有节目单时）
            if (down && mProgramListVisible) {
                moveFocusToPrograms();
            }
            return;
        }
        selectGridPosition(next);
    }

    private void selectGridPosition(int position) {
        if (position < 0 || position >= mVisibleIndices.size()) {
            return;
        }
        int old = mGridPosition;
        mGridPosition = position;
        mSelectedChannelIndex = mVisibleIndices.get(position);
        mCardAdapter.setSelection(old, position);
        scrollGridToSelection();
        refreshProgramStrip();
    }

    /** 网格内左右移动一格；在第一列按左时折回分组栏。 */
    private boolean moveGridHorizontal(boolean right) {
        if (mChannels.isEmpty()) {
            return false;
        }
        if (!right && mGridPosition % mSpanCount == 0) {
            moveFocusToGroups();
            return true;
        }
        int next = mGridPosition + (right ? 1 : -1);
        if (next < 0 || next >= mChannels.size()) {
            return true; // 同一行的边上：吃掉按键，光标留在原地
        }
        selectGridPosition(next);
        return true;
    }

    // ------------------------------------------------------------------ 按键入口

    /**
     * 上下键：分组栏、网格、节目单各按各的规则走。
     *
     * @return true 表示按键已被消费
     */
    public boolean handleVertical(boolean down) {
        switch (mFocusPanel) {
            case GROUPS:
                moveGroupSelection(down);
                return true;
            case PROGRAMS:
                // 在节目单里按向上 = 回到频道网格（节目单是横向的，上下没有别的含义）
                if (!down) {
                    moveFocusToChannels();
                }
                return true;
            default:
                moveChannelSelection(down);
                return true;
        }
    }

    /**
     * 左右键：网格里逐格、分组栏里回到网格、节目单里换节目（到头翻日期）。
     */
    public boolean handleHorizontal(boolean right) {
        switch (mFocusPanel) {
            case GROUPS:
                if (right) {
                    moveFocusToChannels();
                }
                return true;
            case PROGRAMS:
                return moveProgramHorizontal(right);
            default:
                return moveGridHorizontal(right);
        }
    }

    /** OK 键。 */
    public void handleOk() {
        switch (mFocusPanel) {
            case GROUPS:
                moveFocusToChannels();
                break;
            case PROGRAMS:
                EpgManager.Program program = getSelectedProgram();
                Channel channel = getSelectedChannel();
                if (channel != null && program != null && mProgramClickListener != null) {
                    mProgramClickListener.onProgramClick(channel, program);
                }
                break;
            default:
                confirmSelection();
                break;
        }
    }

    /** 播放某个频道（OK / 触摸点击卡片）。 */
    public void confirmSelection() {
        Channel selected = getSelectedChannel();
        if (mSelectionListener != null && selected != null) {
            mSelectionListener.onChannelSelected(mSelectedChannelIndex, selected);
        }
    }

    // ------------------------------------------------------------------ 节目单

    private void refreshProgramStrip() {
        Channel selected = getSelectedChannel();
        if (selected == null || mEpgManager == null) {
            hideProgramStrip();
            return;
        }
        // loadEpg 内部有去重与节流，光标每动一格都问一次不会打网络
        mEpgManager.loadEpg(selected);

        List<EpgManager.Program> programs = mEpgManager.getAllPrograms(selected);
        if (programs.isEmpty()) {
            mProgramListVisible = false;
            mProgramAdapter = null;
            mProgramRecyclerView.setAdapter(null);
            mProgramPanel.setVisibility(VISIBLE);
            mProgramSubtitleView.setText(R.string.no_epg_data);
            updateProgramTitle();
            if (mFocusPanel == FocusPanel.PROGRAMS) {
                moveFocusToChannels();
            }
            return;
        }

        long today = EpgCache.localDayNumber(System.currentTimeMillis());
        java.util.TreeSet<Integer> available = new java.util.TreeSet<>();
        for (EpgManager.Program program : programs) {
            int offset = (int) (EpgCache.localDayNumber(program.startTime) - today);
            if (offset >= -MAX_DAY_BACK && offset <= MAX_DAY_AHEAD) {
                available.add(offset);
            }
        }
        mAvailableDayOffsets = new ArrayList<>(available);
        if (!mAvailableDayOffsets.contains(mDayOffset)) {
            mDayOffset = mAvailableDayOffsets.contains(0) ? 0 : nearestOffset();
        }

        List<EpgManager.Program> dayPrograms = programsForDay(programs, mDayOffset);
        mProgramAdapter = new ProgramListAdapter(getContext(), dayPrograms, mProgramScroller);
        mProgramAdapter.setRowClickListener(this::onProgramRowTap);
        mProgramRecyclerView.setAdapter(mProgramAdapter);
        mProgramListVisible = true;
        mProgramPanel.setVisibility(VISIBLE);

        int index = 0;
        long focusTime = mProgramFocusTimeMs > 0 ? mProgramFocusTimeMs : System.currentTimeMillis();
        if (mDayOffset == (int) (EpgCache.localDayNumber(focusTime) - today)) {
            index = nearestProgramIndex(dayPrograms, focusTime);
        }
        mSelectedProgramIndex = index;
        mProgramAdapter.setSelectedIndex(index);
        mProgramAdapter.setFocused(mFocusPanel == FocusPanel.PROGRAMS);
        scrollProgramsTo(index);
        updateProgramTitle();
    }

    private void hideProgramStrip() {
        mProgramListVisible = false;
        mProgramAdapter = null;
        mProgramRecyclerView.setAdapter(null);
        mProgramPanel.setVisibility(GONE);
        if (mFocusPanel == FocusPanel.PROGRAMS) {
            moveFocusToChannels();
        }
    }

    /** 节目单里左右换节目；到头则翻到前一天 / 后一天。 */
    private boolean moveProgramHorizontal(boolean right) {
        if (!mProgramListVisible || mProgramAdapter == null) {
            return true;
        }
        int count = mProgramAdapter.getItemCount();
        int next = mSelectedProgramIndex + (right ? 1 : -1);
        if (next >= 0 && next < count) {
            mSelectedProgramIndex = next;
            mProgramAdapter.setSelectedIndex(next);
            scrollProgramsTo(next);
            return true;
        }
        if (moveProgramDay(right ? 1 : -1)) {
            int landed = right ? 0 : Math.max(0, (mProgramAdapter != null
                    ? mProgramAdapter.getItemCount() - 1 : 0));
            mSelectedProgramIndex = landed;
            if (mProgramAdapter != null) {
                mProgramAdapter.setSelectedIndex(landed);
            }
            scrollProgramsTo(landed);
        }
        return true;
    }

    /** 节目单里上下移动（保留给旧调用方；上下键在节目单里意味着回到网格）。 */
    public boolean moveProgramSelection(boolean down) {
        if (mFocusPanel != FocusPanel.PROGRAMS || mProgramAdapter == null) {
            return false;
        }
        int count = mProgramAdapter.getItemCount();
        if (count == 0) {
            return false;
        }
        int next = Math.max(0, Math.min(count - 1, mSelectedProgramIndex + (down ? 1 : -1)));
        if (next == mSelectedProgramIndex) {
            return false;
        }
        mSelectedProgramIndex = next;
        mProgramAdapter.setSelectedIndex(next);
        scrollProgramsTo(next);
        return true;
    }

    /** 光标落到当天节目单的第一档 / 最后一档。 */
    public void selectProgramEdge(boolean first) {
        if (mProgramAdapter == null || mProgramAdapter.getItemCount() == 0) {
            return;
        }
        int index = first ? 0 : mProgramAdapter.getItemCount() - 1;
        mSelectedProgramIndex = index;
        mProgramAdapter.setSelectedIndex(index);
        scrollProgramsTo(index);
    }

    /**
     * 把节目单对准某个时刻：直播时是"现在"，回看时是被回放节目的开始时间。
     */
    public void setProgramFocusTime(long timeMs) {
        mProgramFocusTimeMs = timeMs;
        long today = EpgCache.localDayNumber(System.currentTimeMillis());
        int offset = (int) (EpgCache.localDayNumber(timeMs) - today);
        if (offset >= -MAX_DAY_BACK && offset <= MAX_DAY_AHEAD) {
            mDayOffset = offset;
        }
        refreshProgramStrip();
    }

    /**
     * 翻到有数据的前一天 / 后一天。
     *
     * @return 真正翻动了返回 true，没有更早（更晚）的数据返回 false
     */
    public boolean moveProgramDay(int delta) {
        int next = nextDayIndex(mAvailableDayOffsets, mDayOffset, delta);
        if (next < 0) {
            return false;
        }
        mDayOffset = mAvailableDayOffsets.get(next);
        refreshProgramStrip();
        return true;
    }

    /** 该步骤会落到哪一天；没有可动的目标返回 -1（边界可单测）。 */
    static int nextDayIndex(List<Integer> availableDays, int currentOffset, int delta) {
        if (availableDays == null || availableDays.isEmpty()) {
            return -1;
        }
        int index = availableDays.indexOf(currentOffset);
        if (index < 0) {
            return -1;
        }
        int next = Math.max(0, Math.min(availableDays.size() - 1, index + delta));
        return next == index ? -1 : next;
    }

    private int nearestOffset() {
        if (mAvailableDayOffsets.isEmpty()) {
            return 0;
        }
        int best = mAvailableDayOffsets.get(0);
        for (Integer offset : mAvailableDayOffsets) {
            if (Math.abs(offset) < Math.abs(best)) {
                best = offset;
            }
        }
        return best;
    }

    private List<EpgManager.Program> programsForDay(List<EpgManager.Program> all, int offset) {
        long target = EpgCache.localDayNumber(System.currentTimeMillis()) + offset;
        List<EpgManager.Program> dayPrograms = new ArrayList<>();
        for (EpgManager.Program program : all) {
            if (EpgCache.localDayNumber(program.startTime) == target) {
                dayPrograms.add(program);
            }
        }
        return dayPrograms;
    }

    /**
     * 覆盖该时刻的节目下标；没有正好覆盖的就退到最近一个已开播的。
     */
    static int nearestProgramIndex(List<EpgManager.Program> dayPrograms, long timeMs) {
        int best = 0;
        for (int i = 0; i < dayPrograms.size(); i++) {
            EpgManager.Program program = dayPrograms.get(i);
            if (timeMs >= program.startTime && timeMs < program.endTime) {
                return i;
            }
            if (program.startTime <= timeMs) {
                best = i;
            } else {
                break;
            }
        }
        return best;
    }

    private void updateProgramTitle() {
        if (mProgramTitleView == null) {
            return;
        }
        if (mDayOffset == 0) {
            mProgramTitleView.setText(R.string.todays_programs);
        } else if (mDayOffset == -1) {
            mProgramTitleView.setText(R.string.yesterday_programs);
        } else {
            java.util.Calendar day = java.util.Calendar.getInstance();
            day.add(java.util.Calendar.DAY_OF_YEAR, mDayOffset);
            mProgramTitleView.setText(java.text.DateFormat.getDateInstance(
                    java.text.DateFormat.MEDIUM).format(day.getTime()));
        }
        Channel selected = getSelectedChannel();
        if (mProgramSubtitleView != null && selected != null) {
            mProgramSubtitleView.setText(getContext().getString(
                    R.string.picker_program_subtitle, selected.name));
        }
    }

    // ------------------------------------------------------------------ 焦点

    public FocusPanel getFocusPanel() {
        return mFocusPanel;
    }

    public void moveFocusToGroups() {
        mFocusPanel = FocusPanel.GROUPS;
        mGroupAdapter.setFocused(true);
        mCardAdapter.setFocused(false);
        mProgramAdapterSetFocused(false);
        applyFocusChrome();
        scrollRailTo(mBrowsedGroup);
    }

    public void moveFocusToChannels() {
        mFocusPanel = FocusPanel.CHANNELS;
        mGroupAdapter.setFocused(false);
        mCardAdapter.setFocused(true);
        mProgramAdapterSetFocused(false);
        mCardAdapter.rebindAfterLayout(mChannelRecyclerView);
        applyFocusChrome();
    }

    public void moveFocusToPrograms() {
        if (!mProgramListVisible || mProgramAdapter == null) {
            return;
        }
        mFocusPanel = FocusPanel.PROGRAMS;
        mGroupAdapter.setFocused(false);
        mCardAdapter.setFocused(false);
        mProgramAdapterSetFocused(true);
        mProgramAdapter.rebindAfterLayout(mProgramRecyclerView);
        applyFocusChrome();
    }

    private void mProgramAdapterSetFocused(boolean focused) {
        if (mProgramAdapter != null) {
            mProgramAdapter.setFocused(focused);
        }
    }

    /** 光标在哪一块，那一块的标题就亮起来（电视上唯一的"当前区域"提示）。 */
    private void applyFocusChrome() {
        if (mGridTitleView != null) {
            mGridTitleView.setTextColor(mFocusPanel == FocusPanel.CHANNELS ? 0xFFFF9966 : 0xFF888888);
        }
        if (mProgramTitleView != null) {
            mProgramTitleView.setTextColor(mFocusPanel == FocusPanel.PROGRAMS ? 0xFFFF9966 : 0xFFFFFFFF);
        }
    }

    private void updateGridTitleForFocus() {
        applyFocusChrome();
    }

    public boolean isGroupColumnVisible() {
        return mRailSection != null && mRailSection.getVisibility() == VISIBLE;
    }

    // ------------------------------------------------------------------ 状态访问

    public void setCurrentChannel(int channelIndex) {
        mCurrentChannelIndex = channelIndex;
        mCardAdapter.setCurrentChannel(mVisibleIndices.indexOf(channelIndex));
    }

    public int getSelectedChannelIndex() {
        return mSelectedChannelIndex;
    }

    @Nullable
    public Channel getSelectedChannel() {
        int position = mVisibleIndices.indexOf(mSelectedChannelIndex);
        return position >= 0 && position < mChannels.size() ? mChannels.get(position) : null;
    }

    @Nullable
    public EpgManager.Program getSelectedProgram() {
        return mProgramAdapter != null ? mProgramAdapter.getProgram(mSelectedProgramIndex) : null;
    }

    public int getBrowsedGroupIndex() {
        return mBrowsedGroup;
    }

    /** 收藏变化后重读：卡片上的星标，以及"收藏"分组本身的内容。 */
    public void onFavoritesChanged() {
        ChannelNavigator.Group group = mNavigator.group(mBrowsedGroup);
        if (group != null && group.favorites) {
            showGroup(mBrowsedGroup, mSelectedChannelIndex);
        } else {
            mCardAdapter.notifyDataSetChanged();
        }
        mGroupAdapter.notifyDataSetChanged();
    }

    /** 内置分组由 App 命名，播放列表自带的分组用自己的名字。 */
    static String groupLabel(Context context, ChannelNavigator.Group group) {
        if (group.favorites) {
            return "★ " + context.getString(R.string.group_favorites);
        }
        if (group.all) {
            return context.getString(R.string.group_all);
        }
        return group.name;
    }

    /** 把单行标签的跑马灯接到统一调度器上（与该行是否高亮绑定）。 */
    static void applyMarquee(RowLabelScroller scroller, TextView view, boolean scrolling) {
        if (scroller == null) {
            return;
        }
        scroller.attach(view, scrolling);
    }

    // ------------------------------------------------------------------ 触摸

    private void onGroupRowTap(int position) {
        if (position < 0 || position >= mNavigator.groupCount()) {
            return;
        }
        showGroup(position, mSelectedChannelIndex);
        moveFocusToChannels();
    }

    private void onCardTap(int position) {
        selectGridPosition(position);
        confirmSelection();
    }

    private void onProgramRowTap(int position) {
        if (mProgramAdapter == null || position < 0 || position >= mProgramAdapter.getItemCount()) {
            return;
        }
        mSelectedProgramIndex = position;
        mProgramAdapter.setSelectedIndex(position);
        if (mProgramClickListener != null) {
            mProgramClickListener.onProgramClick(getSelectedChannel(),
                    mProgramAdapter.getProgram(position));
        }
    }

    // ------------------------------------------------------------------ 滚动

    private void scrollGridToSelection() {
        if (mChannelRecyclerView == null) {
            return;
        }
        final int position = mGridPosition;
        mChannelRecyclerView.post(() -> {
            RecyclerView.LayoutManager manager = mChannelRecyclerView.getLayoutManager();
            if (!(manager instanceof GridLayoutManager)) {
                return;
            }
            GridLayoutManager grid = (GridLayoutManager) manager;
            int row = position / mSpanCount;
            int first = grid.findFirstVisibleItemPosition();
            int last = grid.findLastVisibleItemPosition();
            int firstRow = first == RecyclerView.NO_POSITION ? row : first / mSpanCount;
            int lastRow = last == RecyclerView.NO_POSITION ? row : last / mSpanCount;
            int visibleRows = Math.max(1, lastRow - firstRow + 1);
            // 光标停在可见区中间一行：上下各留半屏，换台时不会一格格推着走
            int targetRow = row - visibleRows / 2;
            grid.scrollToPositionWithOffset(Math.max(0, targetRow) * mSpanCount, 0);
        });
    }

    private void scrollProgramsTo(int position) {
        if (mProgramRecyclerView == null) {
            return;
        }
        mProgramRecyclerView.post(() -> {
            RecyclerView.LayoutManager manager = mProgramRecyclerView.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(
                        Math.max(0, position - 1), 0);
            }
        });
    }

    private void scrollRailTo(int index) {
        if (mGroupRecyclerView == null) {
            return;
        }
        mGroupRecyclerView.post(() -> {
            RecyclerView.LayoutManager manager = mGroupRecyclerView.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(
                        Math.max(0, index - 1), 0);
            }
        });
    }

    // ------------------------------------------------------------------ 显隐

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        // 该组件由 MainActivity 直接 setVisibility 控制，这里只做入场动效
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            mCardScroller.stop();
            mProgramScroller.stop();
            return;
        }
        setAlpha(0f);
        animate().alpha(1f).setDuration(160).start();
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        // 由 MainActivity 驱动，不接播放器控制层
    }

    @Nullable
    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onPlayStateChanged(int playState) {
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
    }

    @Override
    public void setProgress(int duration, int position) {
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }

    // ------------------------------------------------------------------ 适配器

    interface RowClickCallback {
        void onRowClick(int position);
    }

    /** 左侧分组栏。 */
    public static class GroupListAdapter extends RecyclerView.Adapter<GroupListAdapter.GroupViewHolder> {
        private final Context mContext;
        private final ChannelNavigator mNavigator;
        private int mSelectedIndex = ChannelNavigator.FAVORITES_GROUP;
        private boolean mFocused = false;
        private RowClickCallback mRowClickCallback;

        GroupListAdapter(Context context, ChannelNavigator navigator) {
            mContext = context;
            mNavigator = navigator;
        }

        void setRowClickListener(RowClickCallback callback) {
            mRowClickCallback = callback;
        }

        void setSelectedGroup(int index) {
            if (mSelectedIndex == index) {
                return;
            }
            int old = mSelectedIndex;
            mSelectedIndex = index;
            notifyItemChanged(old);
            notifyItemChanged(mSelectedIndex);
        }

        void setFocused(boolean focused) {
            if (mFocused != focused) {
                mFocused = focused;
                notifyDataSetChanged();
            }
        }

        @NonNull
        @Override
        public GroupViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(mContext);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, dp2px(RAIL_ROW_HEIGHT_DP)));
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp2px(12), 0, dp2px(10), 0);
            view.setFocusable(false);
            view.setClickable(true);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return new GroupViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull GroupViewHolder holder, int position) {
            ChannelNavigator.Group group = mNavigator.group(position);
            if (group == null) {
                return;
            }
            holder.bind(groupLabel(mContext, group), group.channels.size(),
                    position == mSelectedIndex, mFocused);
            holder.itemView.setOnClickListener(v -> {
                if (mRowClickCallback != null) {
                    mRowClickCallback.onRowClick(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mNavigator.groupCount();
        }

        public static class GroupViewHolder extends RecyclerView.ViewHolder {
            private final TextView nameView;

            GroupViewHolder(TextView itemView) {
                super(itemView);
                nameView = itemView;
            }

            void bind(String label, int count, boolean selected, boolean focused) {
                nameView.setText(label + "  " + count);
                if (selected && focused) {
                    nameView.setTextColor(0xFFFFFFFF);
                    nameView.setTextSize(16);
                    nameView.setBackgroundResource(R.drawable.bg_rail_focused);
                } else if (selected) {
                    nameView.setTextColor(0xFFFF9966);
                    nameView.setTextSize(16);
                    nameView.setBackgroundResource(R.drawable.bg_rail_focused);
                    nameView.getBackground().setAlpha(80);
                } else {
                    nameView.setTextColor(0xFFCCCCCC);
                    nameView.setTextSize(15);
                    nameView.setBackgroundResource(R.drawable.bg_rail_normal);
                }
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }

    /** 右侧频道网格里的一张卡片。 */
    public static class ChannelCardAdapter extends RecyclerView.Adapter<ChannelCardAdapter.CardViewHolder> {
        private final Context mContext;
        private final ChannelNavigator mNavigator;
        private final RowLabelScroller mScroller;
        private List<Channel> mChannels = new ArrayList<>();
        private List<Integer> mNumbers = new ArrayList<>();
        private int mCurrentPosition = -1;
        private int mSelectedPosition = 0;
        private boolean mFocused = true;
        private RowClickCallback mRowClickCallback;

        ChannelCardAdapter(Context context, ChannelNavigator navigator, RowLabelScroller scroller) {
            mContext = context;
            mNavigator = navigator;
            mScroller = scroller;
        }

        void setRowClickListener(RowClickCallback callback) {
            mRowClickCallback = callback;
        }

        void updateData(List<Channel> channels, List<Integer> flatIndices,
                        int currentPosition, int selectedPosition, boolean focused) {
            mChannels = new ArrayList<>(channels);
            mNumbers = new ArrayList<>(flatIndices);
            mCurrentPosition = currentPosition;
            mSelectedPosition = selectedPosition;
            mFocused = focused;
            notifyDataSetChanged();
        }

        void setCurrentChannel(int position) {
            int old = mCurrentPosition;
            mCurrentPosition = position;
            if (old >= 0) {
                notifyItemChanged(old);
            }
            if (mCurrentPosition >= 0) {
                notifyItemChanged(mCurrentPosition);
            }
        }

        void setSelection(int oldPosition, int newPosition) {
            mSelectedPosition = newPosition;
            if (oldPosition >= 0) {
                notifyItemChanged(oldPosition);
            }
            if (newPosition >= 0) {
                notifyItemChanged(newPosition);
            }
        }

        void setFocused(boolean focused) {
            if (mFocused != focused) {
                mFocused = focused;
                notifyDataSetChanged();
            }
        }

        void rebindAfterLayout(View list) {
            list.post(this::notifyDataSetChanged);
        }

        @NonNull
        @Override
        public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(mContext);
            int margin = dp2px(5);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, dp2px(CARD_HEIGHT_DP));
            lp.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(lp);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp2px(10), 0, dp2px(8), 0);
            card.setFocusable(false);
            card.setClickable(true);
            return new CardViewHolder(card, mContext, mScroller);
        }

        @Override
        public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
            Channel channel = mChannels.get(position);
            int number = position < mNumbers.size() ? mNumbers.get(position) + 1 : 0;
            holder.bind(channel, number,
                    position == mSelectedPosition && mFocused,
                    position == mSelectedPosition,
                    position == mCurrentPosition,
                    mNavigator != null && mNavigator.isFavorite(channel));
            holder.itemView.setOnClickListener(v -> {
                if (mRowClickCallback != null) {
                    mRowClickCallback.onRowClick(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }

        public static class CardViewHolder extends RecyclerView.ViewHolder {
            private final View playingBar;
            private final TextView numberView;
            private final ImageView logoView;
            private final TextView nameView;
            private final TextView starView;
            private final RowLabelScroller mScroller;

            CardViewHolder(LinearLayout itemView, Context context, RowLabelScroller scroller) {
                super(itemView);
                mScroller = scroller;

                // 正在播放的标记：卡片左侧一根橙色竖条
                playingBar = new View(context);
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                        dp2px(context, 3), dp2px(context, 30));
                barLp.rightMargin = dp2px(context, 8);
                itemView.addView(playingBar, barLp);

                logoView = new ImageView(context);
                logoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                        dp2px(context, 30), dp2px(context, 30));
                logoLp.rightMargin = dp2px(context, 8);
                itemView.addView(logoView, logoLp);

                LinearLayout textColumn = new LinearLayout(context);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                itemView.addView(textColumn);

                nameView = new TextView(context);
                nameView.setTextColor(0xFFFFFFFF);
                nameView.setTextSize(15);
                nameView.setSingleLine(true);
                nameView.setEllipsize(TextUtils.TruncateAt.END);
                textColumn.addView(nameView);

                numberView = new TextView(context);
                numberView.setTextColor(0xFF888888);
                numberView.setTextSize(11);
                numberView.setSingleLine(true);
                textColumn.addView(numberView);

                starView = new TextView(context);
                starView.setText("★");
                starView.setTextColor(0xFFFF6B35);
                starView.setTextSize(12);
                itemView.addView(starView);
            }

            void bind(Channel channel, int number, boolean focused, boolean selected,
                      boolean playing, boolean favorite) {
                nameView.setText(channel.name);
                nameView.setTextColor(focused ? 0xFFFFFFFF : 0xFFFFFFFF);
                nameView.setTextSize(focused ? 16 : 15);
                numberView.setText(number > 0 ? String.valueOf(number) : "");
                starView.setVisibility(favorite ? View.VISIBLE : View.GONE);

                View bar = playingBar;
                if (bar != null) {
                    bar.setBackgroundColor(playing ? 0xFFFF6B35 : 0x00000000);
                }

                if (focused) {
                    itemView.setBackgroundResource(R.drawable.bg_card_focused);
                } else if (playing) {
                    itemView.setBackgroundResource(R.drawable.bg_card_current);
                } else if (selected) {
                    itemView.setBackgroundResource(R.drawable.bg_card_focused);
                    itemView.getBackground().setAlpha(140);
                } else {
                    itemView.setBackgroundResource(R.drawable.bg_card_normal);
                }

                if (channel.logo != null && !channel.logo.isEmpty()) {
                    Glide.with(itemView)
                            .load(channel.logo)
                            .centerInside()
                            .into(logoView);
                } else {
                    logoView.setImageDrawable(null);
                }

                // 只有光标所在的那张卡片滚动长名字，其余保持省略号
                applyMarquee(mScroller, nameView, focused);
            }

            private static int dp2px(Context context, int dp) {
                return Math.round(context.getResources().getDisplayMetrics().density * dp);
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }

    /** 底部节目单（横向）。第一列必须是时间，测试依赖这个结构。 */
    public static class ProgramListAdapter extends RecyclerView.Adapter<ProgramListAdapter.ProgramViewHolder> {
        private final Context mContext;
        private final List<EpgManager.Program> mPrograms;
        private final RowLabelScroller mScroller;
        private int mSelectedIndex = -1;
        private boolean mFocused = false;
        private RowClickCallback mRowClickCallback;

        public ProgramListAdapter(Context context, List<EpgManager.Program> programs,
                                  RowLabelScroller scroller) {
            mContext = context;
            mScroller = scroller;
            mPrograms = new ArrayList<>(programs);
        }

        void setRowClickListener(RowClickCallback callback) {
            mRowClickCallback = callback;
        }

        public void setSelectedIndex(int index) {
            if (mSelectedIndex == index) {
                return;
            }
            int old = mSelectedIndex;
            mSelectedIndex = index;
            if (old >= 0 && old < mPrograms.size()) {
                notifyItemChanged(old);
            }
            if (mSelectedIndex >= 0 && mSelectedIndex < mPrograms.size()) {
                notifyItemChanged(mSelectedIndex);
            }
        }

        public void setFocused(boolean focused) {
            if (mFocused != focused) {
                mFocused = focused;
                notifyDataSetChanged();
            }
        }

        void rebindAfterLayout(View list) {
            list.post(this::notifyDataSetChanged);
        }

        @NonNull
        @Override
        public ProgramViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout view = new LinearLayout(mContext);
            view.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    dp2px(PROGRAM_ITEM_WIDTH_DP), RecyclerView.LayoutParams.MATCH_PARENT);
            lp.setMargins(dp2px(4), 0, dp2px(4), 0);
            view.setLayoutParams(lp);
            view.setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8));
            view.setFocusable(false);
            view.setClickable(true);
            return new ProgramViewHolder(view, mContext, mScroller);
        }

        @Override
        public void onBindViewHolder(@NonNull ProgramViewHolder holder, int position) {
            holder.bind(mPrograms.get(position), position == mSelectedIndex, mFocused);
            holder.itemView.setOnClickListener(v -> {
                if (mRowClickCallback != null) {
                    mRowClickCallback.onRowClick(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mPrograms.size();
        }

        @Nullable
        EpgManager.Program getProgram(int index) {
            return index >= 0 && index < mPrograms.size() ? mPrograms.get(index) : null;
        }

        public static class ProgramViewHolder extends RecyclerView.ViewHolder {
            /** 正在播出的时间戳用强调色。 */
            public static final int TIME_CURRENT = 0xFFFF9966;
            /** 其余时间戳安静一些。 */
            public static final int TIME_NORMAL = 0xFF888888;

            private final TextView timeView;
            private final TextView titleView;
            private final RowLabelScroller mScroller;

            ProgramViewHolder(LinearLayout itemView, Context context, RowLabelScroller scroller) {
                super(itemView);
                mScroller = scroller;

                timeView = new TextView(context);
                timeView.setTextColor(TIME_NORMAL);
                timeView.setTextSize(12);
                timeView.setSingleLine(true);
                itemView.addView(timeView);

                titleView = new TextView(context);
                titleView.setTextColor(0xFFCCCCCC);
                titleView.setTextSize(14);
                titleView.setMaxLines(1);
                titleView.setEllipsize(TextUtils.TruncateAt.END);
                itemView.addView(titleView);
            }

            void bind(EpgManager.Program program, boolean selected, boolean focused) {
                titleView.setText(program.title);
                if (program.startTime > 0) {
                    SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
                    timeView.setText(fmt.format(new Date(program.startTime)));
                } else {
                    timeView.setText("");
                }

                long now = System.currentTimeMillis();
                boolean live = now >= program.startTime && now < program.endTime;
                timeView.setTextColor(live ? TIME_CURRENT : TIME_NORMAL);

                if (selected && focused) {
                    itemView.setBackgroundResource(R.drawable.bg_program_focused);
                    titleView.setTextColor(0xFFFFFFFF);
                } else if (selected) {
                    itemView.setBackgroundResource(R.drawable.bg_program_focused);
                    itemView.getBackground().setAlpha(120);
                    titleView.setTextColor(0xFFFFFFFF);
                } else if (live) {
                    itemView.setBackgroundResource(R.drawable.bg_program_current);
                    titleView.setTextColor(0xFFCCCCCC);
                } else {
                    itemView.setBackgroundColor(0x00000000);
                    titleView.setTextColor(0xFFCCCCCC);
                }

                applyMarquee(mScroller, titleView, selected && focused);
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }
}
