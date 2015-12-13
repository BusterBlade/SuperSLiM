package com.tonicartos.superslim.internal

import android.support.v7.widget.RecyclerView
import android.util.SparseArray
import android.view.View
import com.tonicartos.superslim.AdapterContract
import com.tonicartos.superslim.AnimationState
import com.tonicartos.superslim.Child
import com.tonicartos.superslim.LayoutHelper
import com.tonicartos.superslim.adapter.Section
import com.tonicartos.superslim.internal.layout.HeaderLayoutManager
import java.util.*

internal class SectionData : AdapterContract.SectionData {
    override var hasHeader = false
    override var numChildren = 0
    override var childSections = IntArray(0)
    override var adapterPosition: Int = 0
    override var itemCount: Int = 0
    var subsections: List<SectionState> = ArrayList()
}


internal class GraphManager(private val root: SectionState) {
    fun loadGraph(adapter: AdapterContract) {
        reset()

        val sectionConfigs: List<Section.Config> = adapter.getSections()
        adapter.setSectionIds(sectionConfigs.map { indexSection(it.makeSection()) })

        val sectionData = SectionData()
        for (i in 0..sectionIndex.size()) {
            adapter.populateSection(sectionIndex.keyAt(i), sectionData)
            sectionIndex.valueAt(i).load(sectionData)
        }
    }

    fun reset() {
        sectionsToRemove.clear()
        sectionsToUpdate.clear()
        numSectionsSeen = 0
        sectionIndex.clear()
    }

    /*************************
     * Layout
     *************************/

    fun layout(helper: LayoutHelper) {
        if (helper.isPreLayout) {
            initPreLayout()
        } else {
            initPostLayout()
        }

        root.layout(helper, 0, 0, helper.layoutWidth)

        if (helper.isPreLayout) {
            cleanupPreLayout()
        } else {
            cleanupPostLayout()
        }
    }

    private fun initPreLayout() {
    }

    private fun initPostLayout() {
        //        doSectionMoves()
        doSectionUpdates()
    }

    private fun cleanupPreLayout() {
    }

    private fun cleanupPostLayout() {
        doSectionRemovals()
    }

    /*************************
     * Scheduling section changes
     *************************/

    private data class ScheduledSectionRemoval(val section: Int, val parent: Int, val position: Int)

    //    private data class ScheduledSectionMove(val section: Int, val fromParent: Int, val fromPosition: Int, val toParent: Int, val toPosition: Int)

    private data class ScheduledSectionUpdate(val section: Int, val config: Section.Config)

    private val sectionsToRemove = arrayListOf<ScheduledSectionRemoval>()
    //    private val sectionsToMove = arrayListOf<ScheduledSectionMove>()
    private val sectionsToUpdate = arrayListOf<ScheduledSectionUpdate>()

    fun sectionAdded(parent: Int, position: Int, config: Section.Config): Int {
        val newSection = config.makeSection()
        getSection(parent).insertSection(position, newSection)
        return indexSection(newSection)
    }

    fun queueSectionRemoved(section: Int, parent: Int, position: Int) {
        sectionsToRemove.add(ScheduledSectionRemoval(section, parent, position))
    }

    //    fun queueSectionMoved(section: Int, fromParent: Int, fromPosition: Int, toParent: Int, toPosition: Int) {
    //        sectionsToMove.add(ScheduledSectionMove(section, fromParent, fromPosition, toParent, toPosition))
    //    }

    fun queueSectionUpdated(section: Int, config: Section.Config) {
        sectionsToUpdate.add(ScheduledSectionUpdate(section, config))
    }

    private fun doSectionRemovals() {
        for (remove in sectionsToRemove) {
            getSection(remove.parent).removeSection(getSection(remove.section))
            deIndexSection(remove.section)
        }
        sectionsToRemove.clear()
    }

    //    private fun doSectionMoves() {
    //        for (move in sectionsToMove) {
    //            getSection(move.fromParent).removeSection(move.fromPosition)
    //            getSection(move.toParent).insertSection(move.toPosition, getSection(move.section))
    //        }
    //        sectionsToMove.clear()
    //    }

    private fun doSectionUpdates() {
        for (update in sectionsToUpdate) {
            replaceSection(update.section, update.config.makeSection(getSection(update.section)))
        }
    }

    /*************************
     * Section management
     *************************/

    private var numSectionsSeen = 0
    private val sectionIndex = SparseArray<SectionState>()

    private fun indexSection(section: SectionState): Int {
        val id = numSectionsSeen
        numSectionsSeen += 1
        sectionIndex.put(id, section)
        return id
    }

    private fun deIndexSection(section: Int) {
        sectionIndex.remove(section)
    }

    private fun getSection(id: Int) = sectionIndex[id]

    private fun replaceSection(id: Int, newSection: SectionState) {
        sectionIndex.put(id, newSection)
    }

    /*************************
     * Item events
     *************************/
    fun addItems(eventData: EventData, positionStart: Int, itemCount: Int) {
        val section = getSection(eventData.section)
        if (eventData.action and EventData.HEADER > 0) {
            section.baseConfig.hasHeader = true
        }
        section.addItems(positionStart, itemCount)
    }

    fun removeItems(eventData: EventData, positionStart: Int, itemCount: Int) {
        val section = getSection(eventData.section)
        if (eventData.action and EventData.HEADER > 0) {
            section.baseConfig.hasHeader = false
        }
        section.removeItems(positionStart, itemCount)
    }

    fun moveItems(fromSection: Int, from: Int, toSection: Int, to: Int) {
        getSection(fromSection).removeItems(from, 1)
        getSection(toSection).addItems(to, 1)
    }
}

/**
 * Section data
 */
abstract class SectionState(val baseConfig: Section.Config, oldState: SectionState? = null) {
    /**
     * The height of the section for this layout pass. Only valid after section is laid out, and never use outside the
     * same layout pass.
     */
    var height: Int = 0

    /**
     * Position that is the head of the displayed section content.
     */
    var headPosition: Int = 0
    /**
     * Position that is the tail of the displayed section content.
     */
    var tailPosition: Int = 0

    /**
     * Total number of children. Children does not equate to items as some subsections may be empty.
     */
    var numChildren: Int = 0
        private set
    /**
     * Total number of items in the section, including the header and items in subsections.
     */
    private var totalItems: Int = 0
    /**
     * Sorted list of subsections.
     */
    private val subsections: ArrayList<SectionState>

    private var _adapterPosition: Int = 0
    /**
     * Position of this section in the adapter.
     */
    private var adapterPosition: Int
        get() = _adapterPosition
        set(value) {
            subsections.forEach { it.adapterPosition += value - _adapterPosition }
            _adapterPosition = value
        }

    init {
        if (oldState != null) {
            height = oldState.height
            headPosition = oldState.headPosition
            tailPosition = oldState.tailPosition
            totalItems = oldState.totalItems
            numChildren = oldState.numChildren
            subsections = oldState.subsections
            adapterPosition = oldState.adapterPosition
        } else {
            subsections = ArrayList()
        }
    }

    internal var hasHeader: Boolean
        get() = baseConfig.hasHeader
        set(value) {
            baseConfig.hasHeader = value
        }

    internal fun getHeader(helper: LayoutHelper): ChildInternal? =
            if (hasHeader) {
                ItemChild.wrap(helper.getView(adapterPosition), helper)
            } else {
                null
            }

    fun getChildAt(helper: LayoutHelper, position: Int): ChildInternal {
        // Find preceding subsection, or the section which is the requested child.
        var precedingSubsection: SectionState? = null
        subsections.forEach {
            if (it.adapterPosition < position) {
                precedingSubsection = it
            } else if (it.adapterPosition == position) {
                return SectionChild.wrap(it, helper)
            } else {
                return@forEach
            }
        }

        var itemAdapterPosition = position - (precedingSubsection?.adapterPosition ?: adapterPosition - if (hasHeader) 1 else 0)
        return ItemChild.wrap(helper.getView(itemAdapterPosition), helper)
    }

    final fun layout(helper: LayoutHelper, left: Int, top: Int, right: Int) {
        val subsectionHelper = helper.acquireSubsectionHelper(left, top, right)
        HeaderLayoutManager.onLayout(subsectionHelper, this)
        subsectionHelper.release()
    }

    final internal fun layoutContent(helper: LayoutHelper, left: Int, top: Int, right: Int) {
        val subsectionHelper = helper.acquireSubsectionHelper(left, top, right)
        doLayout(subsectionHelper)
        subsectionHelper.release()
    }

    protected abstract fun doLayout(helper: LayoutHelper)

    infix operator fun contains(viewHolder: RecyclerView.ViewHolder): Boolean = viewHolder.adapterPosition >= adapterPosition && viewHolder.adapterPosition < adapterPosition + totalItems

    /*************************
     * Item management
     *************************/

    internal fun addItems(adapterPositionStart: Int, itemCount: Int) {
        subsections.forEach {
            if (it.adapterPosition > adapterPositionStart) {
                it.adapterPosition += itemCount
            }
        }
        numChildren += itemCount
        totalItems += itemCount
    }

    /**
     * Remove items might actually include subsections. With isn't exactly correct behaviour, but convenient.
     */
    internal fun removeItems(adapterPositionStart: Int, itemCount: Int) {
        val toRemove = arrayListOf<Int>()

        subsections.forEachIndexed { i, it ->
            if (it.adapterPosition > adapterPositionStart) {
                it.adapterPosition -= itemCount
                if (it.adapterPosition < adapterPositionStart) {
                    toRemove.add(i)
                }
            }
        }
        val totalNonChildrenRemoved = toRemove.reduce { r, i -> r + subsections[i].totalItems }
        toRemove.forEach { i -> subsections.removeAt(i) }
        totalItems -= itemCount
        numChildren -= toRemove.size + (itemCount - totalNonChildrenRemoved)
    }

    /*************************
     * Section management
     *************************/

    fun insertSection(position: Int, newSection: SectionState) {
        // Keep subsections in order.
        var insertPoint = 0
        subsections.forEachIndexed { i, it ->
            if (it.adapterPosition < position) {
                insertPoint += 1
            } else {
                it.adapterPosition += newSection.totalItems
            }
        }
        subsections.add(insertPoint, newSection)

        numChildren += 1
        totalItems += newSection.totalItems
    }

    fun removeSection(section: SectionState) {
        subsections.remove(section)
        totalItems -= section.totalItems
        numChildren -= 1
    }

    internal fun load(data: SectionData) {
        numChildren = data.numChildren
        adapterPosition = data.adapterPosition
        hasHeader = data.hasHeader
        totalItems = data.itemCount
        subsections.clear()
        subsections.addAll(data.subsections)
    }
}

abstract class ChildInternal(var helper: LayoutHelper) : Child {
    @AnimationState override var animationState: Int = Child.ANIM_NONE
}

private class SectionChild(var section: SectionState, helper: LayoutHelper) : ChildInternal(helper) {
    companion object {
        val pool = arrayListOf<SectionChild>()

        fun wrap(section: SectionState, helper: LayoutHelper): SectionChild {
            return if (pool.isEmpty()) {
                SectionChild(section, helper)
            } else {
                pool[0].reInit(section, helper)
            }
        }
    }

    private fun reInit(section: SectionState, helper: LayoutHelper): SectionChild {
        this.section = section
        this.helper = helper
        return this
    }

    override fun done() {
        pool.add(this)
    }

    override val isRemoved: Boolean
        get() = false

    private var _measuredWidth: Int = 0
    override val measuredWidth: Int
        get() = _measuredWidth

    override val measuredHeight: Int
        get() = Child.INVALID

    override fun measure(usedWidth: Int, usedHeight: Int) {
        _measuredWidth = helper.layoutWidth - usedWidth
    }

    private var _left = 0
    override val left: Int
        get() = _left

    private var _top = 0
    override val top: Int
        get() = _top

    private var _right = 0
    override val right: Int
        get() = _right

    override val bottom: Int
        get() = Child.INVALID


    override fun layout(left: Int, top: Int, right: Int, bottom: Int) {
        _left = left
        _top = top
        _right = right
        section.layout(helper, left, top, right)
    }

    override val width: Int
        get() = _right - _left
    override val height: Int
        get() = section.height

    override fun addToRecyclerView(i: Int) {
    }
}

private class ItemChild(var view: View, helper: LayoutHelper) : ChildInternal(helper) {
    companion object {
        val pool = arrayListOf<ItemChild>()

        fun wrap(view: View, helper: LayoutHelper): ItemChild {
            return if (pool.isEmpty()) {
                ItemChild(view, helper)
            } else {
                pool[0].reInit(view, helper)
            }
        }
    }

    private fun reInit(view: View, helper: LayoutHelper): ItemChild {
        this.view = view
        this.helper = helper
        return this
    }

    override fun done() {
        pool.add(this)
    }

    override val isRemoved: Boolean
        get() = view.rvLayoutParams.isItemRemoved

    override val measuredWidth: Int
        get() = helper.getMeasuredWidth(view)
    override val measuredHeight: Int
        get() = helper.getMeasuredHeight(view)

    override fun measure(usedWidth: Int, usedHeight: Int) {
        helper.measure(view, usedWidth, usedHeight)
    }

    override val left: Int
        get() = helper.getLeft(view)
    override val top: Int
        get() = helper.getTop(view)
    override val right: Int
        get() = helper.getRight(view)
    override val bottom: Int
        get() = helper.getBottom(view)

    override fun layout(left: Int, top: Int, right: Int, bottom: Int) {
        val helper = this.helper
        helper.layout(view, left, top, right, bottom, 0, 0, 0, 0)
    }

    override val width: Int
        get() = helper.getMeasuredWidth(view)
    override val height: Int
        get() = helper.getMeasuredHeight(view)

    override fun addToRecyclerView(i: Int) {
        val helper = this.helper
        val view = this.view
        when (animationState) {
            Child.ANIM_APPEARING, Child.ANIM_NONE -> helper.addView(view, i)
            Child.ANIM_DISAPPEARING -> helper.addDisappearingView(view, i)
        }
    }
}