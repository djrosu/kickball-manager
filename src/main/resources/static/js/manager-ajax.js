/*
 * Manager JSON API Client
 * -----------------------
 * Handles high-frequency game-management actions without form submissions or
 * page replacement. The initial manager screen is still rendered by Thymeleaf,
 * but every action after that uses /manager/api and receives a JSON snapshot of
 * the authoritative server state.
 *
 * Actions handled here:
 *   - add/remove runs
 *   - move a player up/down
 *   - add/remove a roster player
 *   - end an at-bat / switch teams
 *
 * Next Batter remains in walkup-player.js because it also coordinates the
 * intro -> walk-up audio sequence. It uses the same JSON API and calls the
 * public applyState(...) method exported by this module.
 */
(function (window, document) {
    'use strict';

    let requestInProgress = false;
    let liveEventSource = null;
    let liveReconnectTimer = null;
    let liveWatchdogTimer = null;
    let liveLastActivityAt = 0;
    let liveConnectionGeneration = 0;
    let currentAudioTarget = null;

    /*
     * A manager action initiated on the selected audio device can play directly
     * from its HTTP response. The server may still emit the equivalent SSE
     * command, so these short one-shot guards prevent a duplicate restart.
     */
    let suppressTargetedAudioCommandUntil = 0;
    let suppressAudioStopUntil = 0;
    let suppressBetweenAtBatAudioUntil = 0;

    /*
     * The server sends an SSE heartbeat about every 25 seconds. Sixty seconds
     * without ANY SSE traffic therefore means the stream is no longer healthy.
     */
    const LIVE_STALE_AFTER_MS = 60000;
    const LIVE_WAKE_STALE_AFTER_MS = 35000;
    const LIVE_WATCHDOG_INTERVAL_MS = 15000;
    const LIVE_ERROR_RECONNECT_DELAY_MS = 2500;

    function managerRoot() {
        return document.querySelector('main.page[data-manager-view]');
    }

    function currentContext() {
        const root = managerRoot();
        return {
            view: root ? root.dataset.managerView : '',
            gameWeekId: root && root.dataset.gameWeekId ? Number(root.dataset.gameWeekId) : null,
            managedTeamId: root && root.dataset.managedTeamId ? Number(root.dataset.managedTeamId) : null
        };
    }



    /** Returns a stable id for this browser installation. */
    function audioDeviceId() {
        const storageKey = 'kickballAudioDeviceId';
        let id = window.localStorage.getItem(storageKey);
        if (!id) {
            id = (window.crypto && window.crypto.randomUUID)
                ? window.crypto.randomUUID()
                : 'device-' + Date.now() + '-' + Math.random().toString(16).slice(2);
            window.localStorage.setItem(storageKey, id);
        }
        return id;
    }

    function isThisDeviceAudioTarget() {
        return !!(currentAudioTarget && currentAudioTarget.targeted
            && currentAudioTarget.deviceId === audioDeviceId());
    }

    /**
     * Returns true when this browser is the currently selected shared-audio
     * device. Actions initiated here should play locally instead of depending
     * on an SSE loopback to the same browser.
     */
    function shouldPlayAudioLocallyForThisAction() {
        return !hasDedicatedAudioTarget() || isThisDeviceAudioTarget();
    }

    /**
     * Ignore the next loopback batter-audio command for a short period. This is
     * used only when this device is both the action initiator and audio target.
     */
    function suppressNextTargetedAudioCommand() {
        suppressTargetedAudioCommandUntil = Date.now() + 3000;
    }

    /**
     * Ignore the next loopback audio-stop after we have already stopped locally.
     */
    function suppressNextAudioStop() {
        suppressAudioStopUntil = Date.now() + 3000;
    }

    /**
     * Ignore one same-browser between-at-bat SSE loopback. The identical song
     * will be played locally from the End At-Bat JSON response.
     */
    function suppressNextBetweenAtBatAudio() {
        suppressBetweenAtBatAudioUntil = Date.now() + 3000;
    }

    function hasDedicatedAudioTarget() {
        return !!(currentAudioTarget && currentAudioTarget.targeted);
    }

    /**
     * Adds the shared-audio selector to either manager dashboard without
     * requiring duplicate Thymeleaf markup. The control is inserted beside the
     * existing audio panel and is available to every authorized manager.
     */
    function ensureAudioTargetControl() {
        if (document.querySelector('[data-audio-target-control]')) {
            updateAudioTargetControl();
            return;
        }

        const audioPanel = document.querySelector('.audio-panel');
        if (!audioPanel) {
            return;
        }

        const wrapper = document.createElement('div');
        wrapper.setAttribute('data-audio-target-control', 'true');
        wrapper.className = 'audio-target-control';
        wrapper.innerHTML =
            '<label style="display:flex;align-items:center;gap:.6rem;font-weight:700;cursor:pointer;">' +
            '<input type="checkbox" data-audio-target-checkbox style="width:1.25rem;height:1.25rem;">' +
            '<span>Play all game audio on this device</span></label>' +
            '<div data-audio-target-status class="muted" style="margin-top:.35rem;"></div>';

        audioPanel.insertAdjacentElement('afterend', wrapper);
        const checkbox = wrapper.querySelector('[data-audio-target-checkbox]');
        checkbox.addEventListener('change', async function () {
            const context = currentContext();
            checkbox.disabled = true;
            try {
                if (checkbox.checked) {
                    /*
                     * Start media priming synchronously from this user gesture,
                     * but do NOT wait for it before telling the server that this
                     * browser owns game audio. Waiting for the first silent MP3
                     * to finish was the source of the "first check hangs" race.
                     */
                    let unlockPromise = Promise.resolve(true);

                    if (window.WalkupPlayer
                            && typeof window.WalkupPlayer.unlockForRemotePlayback === 'function') {
                        unlockPromise =
                            window.WalkupPlayer.unlockForRemotePlayback();
                    }

                    const status =
                        document.querySelector('[data-audio-target-status]');
                    if (status) {
                        status.textContent = 'Activating audio on this device...';
                        status.classList.remove('success');
                    }

                    /*
                     * Claim immediately. This request must not depend on how long
                     * the browser takes to load/accept the silent prime clip.
                     */
                    currentAudioTarget = await postJson(
                        '/manager/api/audio-target/claim',
                        {
                            gameWeekId: context.gameWeekId,
                            deviceId: audioDeviceId()
                        });

                    console.debug('[AUDIO] this device claimed game audio', {
                        gameWeekId: context.gameWeekId,
                        deviceId: audioDeviceId()
                    });

                    /*
                     * If game start or mobile browser scheduling left EventSource
                     * in CONNECTING/SUSPENDED state, the watchdog will now verify
                     * it immediately. A healthy connection is left untouched.
                     */
                    recoverLiveSyncAfterWake('audio target claimed');

                    /*
                     * Priming result is diagnostic only. The claim remains valid
                     * even if the browser reports that priming was not accepted;
                     * manual playback can still establish permission later.
                     */
                    unlockPromise.then(function (accepted) {
                        console.debug('[AUDIO] audio target prime result', {
                            accepted: accepted,
                            deviceId: audioDeviceId()
                        });
                    });
                } else {
                    currentAudioTarget = await postJson('/manager/api/audio-target/release', {
                        gameWeekId: context.gameWeekId,
                        deviceId: audioDeviceId()
                    });
                }
                updateAudioTargetControl();
            } catch (error) {
                showMessage(error.message || 'Unable to change the audio target.', true);
                checkbox.checked = isThisDeviceAudioTarget();
            } finally {
                checkbox.disabled = false;
            }
        });
        updateAudioTargetControl();
    }

    function updateAudioTargetControl() {
        const checkbox = document.querySelector('[data-audio-target-checkbox]');
        const status = document.querySelector('[data-audio-target-status]');
        if (!checkbox || !status) {
            return;
        }
        checkbox.checked = isThisDeviceAudioTarget();
        if (!hasDedicatedAudioTarget()) {
            status.textContent = 'Default audio mode: audio plays on the manager device that advances the batter.';
            status.classList.remove('success');
        } else if (isThisDeviceAudioTarget()) {
            status.textContent = 'Audio controller: this device';
            status.classList.add('success');
        } else {
            status.textContent = 'Audio controller: ' +
                (currentAudioTarget.managerName || 'another manager');
            status.classList.remove('success');
        }
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        const headers = { 'Content-Type': 'application/json' };

        if (token && header) {
            headers[header.getAttribute('content')] = token.getAttribute('content');
        }
        return headers;
    }

    /**
     * Converts the existing progressive-enhancement form action into one JSON
     * API endpoint and request body. The old MVC form actions remain in the HTML
     * as a no-JavaScript fallback.
     */
    function describeAction(form) {
        const action = form.getAttribute('action') || '';
        const context = currentContext();
        const body = {
            gameWeekId: context.gameWeekId,
            teamId: null,
            rosterEntryId: null,
            playerId: null
        };
        let endpoint = null;
        let match;

        if ((match = action.match(/\/runs\/(\d+)\/remove(?:\?|$)/))) {
            endpoint = '/manager/api/runs/remove';
            body.rosterEntryId = Number(match[1]);
        } else if ((match = action.match(/\/runs\/(\d+)(?:\?|$)/))) {
            endpoint = '/manager/api/runs/add';
            body.rosterEntryId = Number(match[1]);
        } else if ((match = action.match(/\/lineup\/(\d+)\/up(?:\?|$)/))) {
            endpoint = '/manager/api/lineup/up';
            body.rosterEntryId = Number(match[1]);
        } else if ((match = action.match(/\/lineup\/(\d+)\/down(?:\?|$)/))) {
            endpoint = '/manager/api/lineup/down';
            body.rosterEntryId = Number(match[1]);
        } else if ((match = action.match(/\/roster\/(\d+)\/remove(?:\?|$)/))) {
            endpoint = '/manager/api/roster/remove';
            body.rosterEntryId = Number(match[1]);
        } else if ((match = action.match(/\/teams\/(\d+)\/add-player(?:\?|$)/))) {
            endpoint = '/manager/api/roster/add';
            body.teamId = Number(match[1]);
            const select = form.querySelector('[name="playerId"]');
            body.playerId = select && select.value ? Number(select.value) : null;
        } else if (action.indexOf('/game/start') >= 0) {
            endpoint = '/manager/api/game/start';
            body.teamId = context.managedTeamId;
            body.deviceId = audioDeviceId();
        } else if (action.indexOf('/game/end-at-bat') >= 0) {
            endpoint = '/manager/api/game/end-at-bat';
            body.teamId = context.managedTeamId;
            body.deviceId = audioDeviceId();
        }

        if (!endpoint) {
            return null;
        }

        return {
            endpoint: endpoint,
            body: body,

            /*
             * Client-only routing hint. Do not send it to Spring; it simply
             * tells submitViaApi that this browser can play the selected break
             * song directly from the HTTP response.
             */
            playBetweenAtBatLocally:
                endpoint === '/manager/api/game/end-at-bat'
                && hasDedicatedAudioTarget()
                && isThisDeviceAudioTarget()
        };
    }

    async function postJson(endpoint, body) {
        const response = await fetch(endpoint, {
            method: 'POST',
            credentials: 'same-origin',
            headers: csrfHeaders(),
            body: JSON.stringify(body || {})
        });

        let payload;
        try {
            payload = await response.json();
        } catch (ignored) {
            throw new Error('The server returned an unreadable manager response.');
        }

        if (!response.ok || payload.success === false) {
            throw new Error(payload.message || 'The server could not complete that manager action.');
        }
        return payload;
    }

    function setFormBusy(form, busy) {
        form.querySelectorAll('button, select, input').forEach(function (control) {
            if (busy) {
                control.dataset.ajaxWasDisabled = control.disabled ? 'true' : 'false';
                control.disabled = true;
            } else {
                control.disabled = control.dataset.ajaxWasDisabled === 'true';
                delete control.dataset.ajaxWasDisabled;
            }
        });
    }

    function showMessage(message, isError) {
        let banner = document.querySelector('#manager-ajax-message');
        if (!banner) {
            banner = document.createElement('div');
            banner.id = 'manager-ajax-message';
            banner.style.position = 'sticky';
            banner.style.top = '.5rem';
            banner.style.zIndex = '1000';
            banner.style.marginBottom = '1rem';
            const main = managerRoot();
            if (main) {
                main.prepend(banner);
            }
        }
        banner.className = isError ? 'error' : 'success';
        banner.textContent = message;
    }

    /** Updates every scoreboard value represented on the current page. */
    function renderScores(state) {
        (state.scores || []).forEach(function (score) {
            document.querySelectorAll('[data-score-team-id="' + score.teamId + '"] [data-score-value]')
                .forEach(function (value) {
                    value.textContent = score.runs;
                });
        });
    }

    /** Rebuilds roster rows from the authoritative JSON snapshot. */
    function renderRosters(state) {
        const context = currentContext();
        (state.teams || []).forEach(function (team) {
            const body = document.querySelector('[data-team-roster-body="' + team.teamId + '"]');
            if (!body) {
                return; // Team Manager page intentionally contains only one team.
            }

            body.innerHTML = (team.roster || []).map(function (entry) {
                return rosterRowHtml(entry, context.view, state.gameInProgress);
            }).join('');

            const emptyMessage = document.querySelector('[data-empty-roster-team-id="' + team.teamId + '"]');
            if (emptyMessage) {
                emptyMessage.hidden = team.roster && team.roster.length > 0;
            }
        });
    }

    function rosterRowHtml(entry, view, gameInProgress) {
        const prefix = view === 'supervisor' ? '/manager/supervisor' : '/manager/team';
        const currentClass = entry.currentBatter ? ' current-batter' : '';
        const badgeStyle = entry.currentBatter ? 'display:inline-block' : 'display:none';
        const disabledClass = gameInProgress ? '' : ' is-game-action-disabled';
        const name = escapeHtml(entry.displayName || entry.fullName || 'Player');
        const fullName = escapeHtml(entry.fullName || '');

        return '<tr class="lineup-row' + currentClass + '"'
            + ' data-roster-entry-id="' + entry.rosterEntryId + '"'
            + ' data-team-id="' + entry.teamId + '"'
            + ' data-player-id="' + entry.playerId + '"'
            + (entry.currentBatter ? ' aria-current="true"' : '') + '>'
            + '<td class="lineup-order" data-label="Order">' + entry.battingOrder + '</td>'
            + '<td class="lineup-player-cell mobile-player-cell">'
            + '<div class="mobile-player-card-header">'
            + '<span class="mobile-order-label">#' + entry.battingOrder + '</span>'
            + '<div class="mobile-order-actions">'
            + actionForm(prefix + '/lineup/' + entry.rosterEntryId + '/up',
                '↑', 'secondary order-button', false, '', 'Move player up')
            + actionForm(prefix + '/lineup/' + entry.rosterEntryId + '/down',
                '↓', 'secondary order-button', false, '', 'Move player down')
            + '</div></div>'
            + '<div class="lineup-player-name">' + name + '</div>'
            + '<div class="lineup-details">' + fullName + '</div>'
            + '<div class="mobile-runs-label">Runs: <span>' + entry.runsScored + '</span></div>'
            + '<span class="current-batter-badge" style="' + badgeStyle + '">At Bat</span>'
            + '</td>'
            + '<td class="lineup-runs" data-label="Runs">' + entry.runsScored + '</td>'
            + '<td class="mobile-actions-cell"><div class="lineup-actions">'
            + actionForm(prefix + '/runs/' + entry.rosterEntryId,
                '+ Run', 'run-action-button', !gameInProgress, disabledClass)
            + actionForm(prefix + '/runs/' + entry.rosterEntryId + '/remove',
                '- Run', 'secondary run-action-button', !gameInProgress, disabledClass)
            + '<form action="' + prefix + '/roster/' + entry.rosterEntryId + '/remove" method="post"'
            + ' class="lineup-action-form remove-action-form confirm-remove-player-form"'
            + ' data-player-name="' + escapeAttribute(entry.displayName || entry.fullName || 'this player') + '">'
            + '<button type="submit" class="danger remove-action-button">Remove</button></form>'
            + '</div></td></tr>';
    }

    /**
     * Creates a progressive-enhancement form for dynamically rendered roster rows.
     * The form still works without JavaScript; during normal operation this module
     * intercepts it and calls the JSON API.
     */
    function actionForm(action, label, buttonClass, isDisabled, formClass, ariaLabel) {
        return '<form action="' + action + '" method="post" class="lineup-action-form '
            + (formClass || '') + '">'
            + '<button type="submit" class="' + (buttonClass || '') + '"'
            + (ariaLabel ? ' aria-label="' + escapeAttribute(ariaLabel) + '"' : '')
            + (isDisabled ? ' disabled' : '') + '>' + label + '</button></form>';
    }

    /** Keeps add-player dropdowns synchronized after roster changes. */
    function renderAvailablePlayers(state) {
        document.querySelectorAll('.add-player-form select[name="playerId"]').forEach(function (select) {
            const previousValue = select.value;
            select.innerHTML = '<option value="">Choose a player</option>'
                + (state.availablePlayers || []).map(function (player) {
                    return '<option value="' + player.playerId + '">'
                        + escapeHtml(player.displayName || 'Player') + '</option>';
                }).join('');

            // Restore selection only if it remains available.
            if (previousValue && select.querySelector('option[value="' + cssEscape(previousValue) + '"]')) {
                select.value = previousValue;
            }
        });
    }

    /**
     * Applies one complete API snapshot. Exposed publicly so walkup-player.js can
     * use the exact same renderer after Next Batter.
     */
    /**
     * Returns whether the current HTML was rendered in the live-game layout.
     *
     * Thymeleaf conditionally creates the current-batter controls only while a
     * game exists. JSON updates can change text and roster rows, but they cannot
     * update controls that were never rendered. When a supervisor starts, ends,
     * resumes, or restarts a game, one automatic refresh switches the remote
     * browser into the correct layout. Normal scoring/lineup updates remain
     * fully asynchronous and do not reload the page.
     */
    function pageWasRenderedForLiveGame() {
        return document.getElementById('current-inning') !== null;
    }

    function requiresLifecycleRefresh(state) {
        return Boolean(state && state.gameInProgress) !== pageWasRenderedForLiveGame();
    }

    function applyState(state, options) {
        options = options || {};

        if (requiresLifecycleRefresh(state)) {
            // Replace the server-rendered pre-game/completed markup with the
            // correct live-game markup (or vice versa). This happens only when
            // the game lifecycle changes, never for ordinary manager actions.
            disconnectLiveSync();
            window.location.reload();
            return;
        }
        renderScores(state);
        renderRosters(state);
        renderAvailablePlayers(state);

        if (window.WalkupPlayer && typeof window.WalkupPlayer.applyDashboardState === 'function') {
            window.WalkupPlayer.applyDashboardState(state, !!options.playAudio);
        }
        if (window.WalkupPlayer && typeof window.WalkupPlayer.init === 'function') {
            window.WalkupPlayer.init();
        }

        document.dispatchEvent(new CustomEvent('manager:state-updated', { detail: state }));
    }

    async function submitViaApi(form, description) {
        if (requestInProgress) {
            return;
        }

        if (description.endpoint === '/manager/api/roster/add' && !description.body.playerId) {
            showMessage('Choose a player before adding to the roster.', true);
            return;
        }

        requestInProgress = true;
        setFormBusy(form, true);
        try {
            const playBetweenAtBatLocally =
                description.playBetweenAtBatLocally === true;

            if (playBetweenAtBatLocally) {
                /*
                 * Stop whatever was playing before switching sides. The server
                 * will also send this same browser an SSE break-song event, so
                 * suppress that one loopback before sending the request.
                 */
                if (window.WalkupPlayer) {
                    window.WalkupPlayer.stop();
                }
                suppressNextBetweenAtBatAudio();
            }

            const state = await postJson(description.endpoint, description.body);
            applyState(state, { playAudio: false });
            showMessage(state.message || 'Manager action completed.', false);

            if (playBetweenAtBatLocally
                    && state.betweenAtBatAudioUrl
                    && window.WalkupPlayer) {
                const statusElement =
                    document.querySelector('#walkup-status');

                console.debug(
                    '[AUDIO] starting self-target between-at-bat song from HTTP response',
                    {
                        audioUrl: state.betweenAtBatAudioUrl,
                        deviceId: audioDeviceId()
                    });

                window.WalkupPlayer
                    .playStandalone(
                        state.betweenAtBatAudioUrl,
                        'Playing between-at-bat music...',
                        statusElement)
                    .then(function (completedNormally) {
                        /*
                         * Continue the alphabetical playlist exactly like the SSE
                         * path does. A later batter action cancels this sequence.
                         */
                        if (completedNormally) {
                            return requestNextBetweenAtBatSong();
                        }
                        return null;
                    })
                    .catch(function () {
                        // WalkupPlayer already shows the actionable media message.
                    });
            }
        } catch (error) {
            showMessage(error.message || 'Unable to complete the manager action.', true);
        } finally {
            setFormBusy(form, false);
            requestInProgress = false;
        }
    }

    document.addEventListener('submit', function (event) {
        const form = event.target;
        if (!(form instanceof HTMLFormElement) || form.matches('[data-next-batter-form]')) {
            return;
        }

        const description = describeAction(form);
        if (!description || event.defaultPrevented) {
            return;
        }

        event.preventDefault();
        submitViaApi(form, description);
    });

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    function escapeAttribute(value) {
        return escapeHtml(value);
    }

    function cssEscape(value) {
        if (window.CSS && typeof window.CSS.escape === 'function') {
            return window.CSS.escape(String(value));
        }
        return String(value).replace(/"/g, '\\"');
    }


    /**
     * Called by the assigned audio browser whenever one between-at-bat MP3 ends.
     * The server verifies that this device still owns the active break-music
     * session before sending another song.
     */
    function requestNextBetweenAtBatSong() {
        const context = currentContext();
        return postJson('/manager/api/audio/between-at-bat/next', {
            gameWeekId: context.gameWeekId,
            teamId: context.managedTeamId,
            deviceId: audioDeviceId()
        });
    }

    /**
     * Records actual traffic on the SSE connection.
     *
     * <p>readyState alone is not sufficient: Chrome can report an EventSource
     * as CONNECTING or OPEN after the underlying request has entered
     * ERR_NETWORK_IO_SUSPENDED. Heartbeats give us an application-level health
     * signal instead.</p>
     */
    function markLiveActivity(eventName, generation) {
        if (generation !== liveConnectionGeneration) {
            return;
        }

        liveLastActivityAt = Date.now();

        if (liveReconnectTimer) {
            window.clearTimeout(liveReconnectTimer);
            liveReconnectTimer = null;
        }

        document.documentElement.dataset.managerLiveSync = 'connected';

        if (eventName && eventName !== 'heartbeat') {
            console.debug('[LIVE] SSE activity', {
                event: eventName,
                gameWeekId: currentContext().gameWeekId,
                deviceId: audioDeviceId()
            });
        }
    }

    /**
     * Closes only the current EventSource. Incrementing the generation makes
     * delayed events from the old stream harmless.
     */
    function closeLiveEventSource() {
        liveConnectionGeneration += 1;

        if (liveEventSource) {
            try {
                liveEventSource.close();
            } catch (ignored) {
                // EventSource.close() is normally safe; nothing else is needed.
            }
            liveEventSource = null;
        }
    }

    /**
     * Schedules one forced reconnect. A healthy event arriving before the timer
     * fires cancels it through markLiveActivity(...).
     */
    function scheduleLiveReconnect(reason, delayMs) {
        if (liveReconnectTimer) {
            return;
        }

        document.documentElement.dataset.managerLiveSync = 'reconnecting';

        console.debug('[LIVE] scheduling SSE reconnect', {
            reason: reason,
            delayMs: delayMs
        });

        liveReconnectTimer = window.setTimeout(function () {
            liveReconnectTimer = null;
            connectLiveSync(reason);
        }, delayMs);
    }

    /**
     * Opens one authenticated Server-Sent Events stream for the selected game.
     *
     * <p>The browser's native EventSource retry remains useful, but we no longer
     * depend on it exclusively. The watchdog below will force a fresh connection
     * whenever actual heartbeat traffic stops.</p>
     */
    function connectLiveSync(reason) {
        const context = currentContext();
        if (!context.gameWeekId || typeof window.EventSource === 'undefined') {
            return;
        }

        if (liveReconnectTimer) {
            window.clearTimeout(liveReconnectTimer);
            liveReconnectTimer = null;
        }

        closeLiveEventSource();

        const generation = ++liveConnectionGeneration;
        liveLastActivityAt = Date.now();

        const url = '/manager/api/live/events?gameWeekId='
            + encodeURIComponent(context.gameWeekId)
            + '&deviceId='
            + encodeURIComponent(audioDeviceId());

        console.debug('[LIVE] opening manager SSE connection', {
            reason: reason || 'initial',
            gameWeekId: context.gameWeekId,
            deviceId: audioDeviceId(),
            generation: generation
        });

        const eventSource = new EventSource(url);
        liveEventSource = eventSource;

        eventSource.addEventListener('connected', function () {
            markLiveActivity('connected', generation);

            console.debug('[AUDIO] manager SSE connected', {
                gameWeekId: context.gameWeekId,
                deviceId: audioDeviceId()
            });
        });

        /*
         * Server heartbeat is the primary watchdog signal. It deliberately does
         * no UI work; simply receiving it proves the stream is alive.
         */
        eventSource.addEventListener('heartbeat', function () {
            markLiveActivity('heartbeat', generation);
        });

        eventSource.addEventListener('dashboard-state', function (event) {
            markLiveActivity('dashboard-state', generation);

            let state;
            try {
                state = JSON.parse(event.data);
            } catch (error) {
                console.warn('Ignored an unreadable live manager update.', error);
                return;
            }

            // A stale tab or changed route must never apply another game's state.
            const latestContext = currentContext();
            if (!latestContext.gameWeekId
                    || Number(state.gameWeekId) !== latestContext.gameWeekId) {
                return;
            }

            applyState(state, { playAudio: false });
            ensureAudioTargetControl();
        });

        eventSource.addEventListener('audio-target-state', function (event) {
            markLiveActivity('audio-target-state', generation);

            try {
                currentAudioTarget = JSON.parse(event.data);
                console.debug(
                    '[AUDIO] received audio target state',
                    currentAudioTarget);
                updateAudioTargetControl();
            } catch (error) {
                console.warn(
                    'Ignored an unreadable audio-target update.',
                    error);
            }
        });

        eventSource.addEventListener('audio-command', function (event) {
            markLiveActivity('audio-command', generation);

            try {
                const command = JSON.parse(event.data);
                if (command.targetDeviceId !== audioDeviceId()) {
                    return;
                }

                if (Date.now() < suppressTargetedAudioCommandUntil) {
                    suppressTargetedAudioCommandUntil = 0;
                    console.debug(
                        '[AUDIO] ignored local loopback batter command; '
                        + 'HTTP response is already playing it');
                    return;
                }

                console.debug(
                    '[AUDIO] received targeted batter audio command',
                    {
                        deviceId: audioDeviceId(),
                        playerName:
                            command.batter && command.batter.playerName
                    });

                if (window.WalkupPlayer && command.batter) {
                    const statusElement =
                        document.querySelector('#walkup-status');

                    window.WalkupPlayer
                        .playSequence(command.batter, statusElement)
                        .catch(function () {
                            // WalkupPlayer displays the actionable media message.
                        });
                }
            } catch (error) {
                console.warn('Ignored an unreadable audio command.', error);
            }
        });

        eventSource.addEventListener('between-at-bat-audio', function (event) {
            markLiveActivity('between-at-bat-audio', generation);

            try {
                const command = JSON.parse(event.data);
                if (command.targetDeviceId !== audioDeviceId()) {
                    return;
                }

                if (Date.now() < suppressBetweenAtBatAudioUntil) {
                    suppressBetweenAtBatAudioUntil = 0;
                    console.debug(
                        '[AUDIO] ignored self-target between-at-bat SSE loopback; '
                        + 'HTTP response will play the same song');
                    return;
                }

                if (window.WalkupPlayer && command.audioUrl) {
                    const statusElement =
                        document.querySelector('#walkup-status');

                    console.debug(
                        '[AUDIO] received between-at-bat audio command',
                        {
                            deviceId: audioDeviceId(),
                            audioUrl: command.audioUrl
                        });

                    window.WalkupPlayer
                        .playStandalone(
                            command.audioUrl,
                            'Playing between-at-bat music...',
                            statusElement)
                        .then(function (completedNormally) {
                            /*
                             * Continue only when this song naturally ended.
                             * Next/Previous Batter cancels the old audio session.
                             */
                            if (completedNormally) {
                                return requestNextBetweenAtBatSong();
                            }
                            return null;
                        })
                        .catch(function () {
                            // WalkupPlayer shows the browser/media error.
                        });
                }
            } catch (error) {
                console.warn(
                    'Ignored an unreadable between-at-bat audio command.',
                    error);
            }
        });

        eventSource.addEventListener('audio-stop', function () {
            markLiveActivity('audio-stop', generation);

            if (Date.now() < suppressAudioStopUntil) {
                suppressAudioStopUntil = 0;
                console.debug(
                    '[AUDIO] ignored local loopback stop; audio was already stopped locally');
                return;
            }

            console.debug('[AUDIO] received audio-stop command', {
                deviceId: audioDeviceId()
            });

            if (window.WalkupPlayer) {
                window.WalkupPlayer.stop();
            }
        });

        eventSource.onerror = function () {
            if (generation !== liveConnectionGeneration) {
                return;
            }

            document.documentElement.dataset.managerLiveSync = 'reconnecting';

            console.debug('[LIVE] manager SSE error/reconnect state', {
                gameWeekId: context.gameWeekId,
                deviceId: audioDeviceId(),
                readyState: eventSource.readyState,
                millisecondsSinceActivity:
                    Date.now() - liveLastActivityAt
            });

            /*
             * Do not wait forever for Chrome's internal retry. If native retry
             * succeeds first, the connected/heartbeat event cancels this timer.
             */
            scheduleLiveReconnect(
                'EventSource error',
                LIVE_ERROR_RECONNECT_DELAY_MS);
        };

        ensureLiveWatchdog();
    }

    /**
     * Every 15 seconds, verify that a heartbeat or another SSE event arrived
     * recently. Avoid reconnect churn while the document is intentionally hidden;
     * wake/focus handlers below perform an immediate health check instead.
     */
    function ensureLiveWatchdog() {
        if (liveWatchdogTimer) {
            return;
        }

        liveWatchdogTimer = window.setInterval(function () {
            if (document.visibilityState === 'hidden') {
                return;
            }

            const context = currentContext();
            if (!context.gameWeekId) {
                return;
            }

            const age = Date.now() - liveLastActivityAt;
            if (!liveEventSource || age > LIVE_STALE_AFTER_MS) {
                console.debug('[LIVE] watchdog detected stale SSE', {
                    gameWeekId: context.gameWeekId,
                    ageMs: age,
                    readyState:
                        liveEventSource ? liveEventSource.readyState : null
                });

                connectLiveSync('watchdog stale connection');
            }
        }, LIVE_WATCHDOG_INTERVAL_MS);
    }

    /**
     * Called when a sleeping/backgrounded browser becomes active again.
     *
     * <p>If no heartbeat was seen recently, force a fresh EventSource even when
     * Chrome still claims the old one is OPEN. This specifically addresses
     * ERR_NETWORK_IO_SUSPENDED after device sleep or long inactivity.</p>
     */
    function recoverLiveSyncAfterWake(reason) {
        if (document.visibilityState === 'hidden') {
            return;
        }

        const context = currentContext();
        if (!context.gameWeekId) {
            return;
        }

        const age = Date.now() - liveLastActivityAt;
        const sourceLooksOpen =
            liveEventSource
            && liveEventSource.readyState === EventSource.OPEN;

        if (!sourceLooksOpen || age > LIVE_WAKE_STALE_AFTER_MS) {
            console.debug('[LIVE] wake/focus recovery reconnect', {
                reason: reason,
                gameWeekId: context.gameWeekId,
                ageMs: age,
                readyState:
                    liveEventSource ? liveEventSource.readyState : null
            });

            connectLiveSync(reason);
        }
    }

    /**
     * Normal disconnect used when leaving the manager page.
     */
    function disconnectLiveSync() {
        if (liveReconnectTimer) {
            window.clearTimeout(liveReconnectTimer);
            liveReconnectTimer = null;
        }

        closeLiveEventSource();

        document.documentElement.dataset.managerLiveSync = 'disconnected';
    }

    function shutdownLiveSync() {
        disconnectLiveSync();

        if (liveWatchdogTimer) {
            window.clearInterval(liveWatchdogTimer);
            liveWatchdogTimer = null;
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        ensureAudioTargetControl();
        connectLiveSync('DOMContentLoaded');
    });

    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') {
            recoverLiveSyncAfterWake('document became visible');
        }
    });

    window.addEventListener('focus', function () {
        recoverLiveSyncAfterWake('window focus');
    });

    window.addEventListener('pageshow', function () {
        recoverLiveSyncAfterWake('pageshow');
    });

    window.addEventListener('online', function () {
        recoverLiveSyncAfterWake('browser online');
    });

    window.addEventListener('beforeunload', shutdownLiveSync);

    window.ManagerAjax = {
        postJson: postJson,
        applyState: applyState,
        currentContext: currentContext,
        showMessage: showMessage,
        connectLiveSync: connectLiveSync,
        disconnectLiveSync: disconnectLiveSync,
        recoverLiveSyncAfterWake: recoverLiveSyncAfterWake,
        hasDedicatedAudioTarget: hasDedicatedAudioTarget,
        isThisDeviceAudioTarget: isThisDeviceAudioTarget,
        shouldPlayAudioLocallyForThisAction:
            shouldPlayAudioLocallyForThisAction,
        suppressNextTargetedAudioCommand:
            suppressNextTargetedAudioCommand,
        suppressNextAudioStop: suppressNextAudioStop,
        suppressNextBetweenAtBatAudio: suppressNextBetweenAtBatAudio,
        audioDeviceId: audioDeviceId,

        /**
         * Requests current-batter playback through the server. This is used when
         * a dedicated audio target is active so the click is routed to the owner
         * device instead of playing on the manager device that clicked.
         */
        requestNextBetweenAtBatSong: requestNextBetweenAtBatSong,

        requestRoutedCurrentBatterAudio: function () {
            const context = currentContext();
            return postJson('/manager/api/audio/play-current', {
                gameWeekId: context.gameWeekId,
                teamId: context.managedTeamId
            });
        }
    };
})(window, document);
