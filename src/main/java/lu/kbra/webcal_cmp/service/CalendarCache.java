package lu.kbra.webcal_cmp.service;

import org.springframework.stereotype.Service;

import lu.kbra.webcal_cmp.data.CachedCalendar;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CalendarCache {

	private CachedCalendar events;
	private String transformed;

	public synchronized CachedCalendar get() {
		return this.events;
	}

	public synchronized void set(final CachedCalendar events) {
		this.events = events;
	}

	public synchronized String getTransformed() {
		return this.transformed;
	}

	public synchronized void setTransformed(final String transformed) {
		this.transformed = transformed;
	}

}