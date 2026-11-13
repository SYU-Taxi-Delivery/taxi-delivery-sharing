package com.school.party.domain.community.domain;

/**
 * Public recruitment state shown to every viewer, derived from the stored
 * {@link PostStatus}, the clock and the current headcount. The viewer's own
 * relation (joined, cancelled) is reported separately.
 *
 * <ul>
 *   <li>RECRUITING: open, seats left, before the participation deadline</li>
 *   <li>FULL: every seat taken; reopens when someone cancels before the deadline</li>
 *   <li>CLOSED: the author closed it with the current members</li>
 *   <li>APPLICATION_CLOSED: past the participation deadline, before the reference time</li>
 *   <li>CANCELLED: the author cancelled the whole schedule</li>
 *   <li>FINISHED: the reference time has passed</li>
 *   <li>REMOVED: removed by an administrator</li>
 * </ul>
 */
public enum RecruitmentStatus { RECRUITING, FULL, CLOSED, APPLICATION_CLOSED, CANCELLED, FINISHED, REMOVED }
