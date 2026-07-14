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
    let currentAudioTarget = null;

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
                     * Claiming audio ownership is intentionally a networking-only
                     * operation. Do not load or play a media resource here. The
                     * prior implementation tried to play a tiny data-URI sound to
                     * "prime" the browser, which caused Chrome to report that the
                     * media source was unsuitable on some devices.
                     *
                     * Actual intro/walk-up files are loaded only when a batter
                     * audio command is received or the manager presses Play.
                     */
                    currentAudioTarget = await postJson('/manager/api/audio-target/claim', {
                        gameWeekId: context.gameWeekId,
                        deviceId: audioDeviceId()
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

        return endpoint ? { endpoint: endpoint, body: body } : null;
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
            const state = await postJson(description.endpoint, description.body);
            applyState(state, { playAudio: false });
            showMessage(state.message || 'Manager action completed.', false);
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
     * Opens one authenticated Server-Sent Events stream for the selected game.
     * EventSource automatically sends the normal session cookie and reconnects
     * after brief network interruptions. Remote snapshots never auto-play
     * audio; audio remains controlled by the device that performed the action.
     */
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

    function connectLiveSync() {
        const context = currentContext();
        if (!context.gameWeekId || typeof window.EventSource === 'undefined') {
            return;
        }

        disconnectLiveSync();
        const url = '/manager/api/live/events?gameWeekId=' + encodeURIComponent(context.gameWeekId)
            + '&deviceId=' + encodeURIComponent(audioDeviceId());
        liveEventSource = new EventSource(url);

        liveEventSource.addEventListener('connected', function () {
            document.documentElement.dataset.managerLiveSync = 'connected';
        });

        liveEventSource.addEventListener('dashboard-state', function (event) {
            let state;
            try {
                state = JSON.parse(event.data);
            } catch (error) {
                console.warn('Ignored an unreadable live manager update.', error);
                return;
            }

            // A stale tab or changed route must never apply another game's state.
            const latestContext = currentContext();
            if (!latestContext.gameWeekId || Number(state.gameWeekId) !== latestContext.gameWeekId) {
                return;
            }

            applyState(state, { playAudio: false });
            ensureAudioTargetControl();
        });

        liveEventSource.addEventListener('audio-target-state', function (event) {
            try {
                currentAudioTarget = JSON.parse(event.data);
                updateAudioTargetControl();
            } catch (error) {
                console.warn('Ignored an unreadable audio-target update.', error);
            }
        });

        liveEventSource.addEventListener('audio-command', function (event) {
            try {
                const command = JSON.parse(event.data);
                if (command.targetDeviceId !== audioDeviceId()) {
                    return;
                }
                if (window.WalkupPlayer && command.batter) {
                    const statusElement = document.querySelector('#walkup-status');
                    window.WalkupPlayer.playSequence(command.batter, statusElement).catch(function () {
                        // WalkupPlayer displays the actionable browser message.
                    });
                }
            } catch (error) {
                console.warn('Ignored an unreadable audio command.', error);
            }
        });

        liveEventSource.addEventListener('between-at-bat-audio', function (event) {
            try {
                const command = JSON.parse(event.data);
                if (command.targetDeviceId !== audioDeviceId()) {
                    return;
                }
                if (window.WalkupPlayer && command.audioUrl) {
                    const statusElement = document.querySelector('#walkup-status');
                    window.WalkupPlayer.playStandalone(
                        command.audioUrl,
                        'Playing between-at-bat music...',
                        statusElement
                    ).then(function () {
                        /*
                         * Continue alphabetically until Next/Previous Batter
                         * stops the break-music session on the server.
                         */
                        return requestNextBetweenAtBatSong();
                    }).catch(function () {
                        // WalkupPlayer shows the browser/media error.
                    });
                }
            } catch (error) {
                console.warn('Ignored an unreadable between-at-bat audio command.', error);
            }
        });

        liveEventSource.addEventListener('audio-stop', function () {
            if (window.WalkupPlayer) {
                window.WalkupPlayer.stop();
            }
        });

        liveEventSource.onerror = function () {
            document.documentElement.dataset.managerLiveSync = 'reconnecting';

            // Browsers normally reconnect EventSource automatically. The small
            // fallback below handles cases where a proxy closes it permanently.
            if (liveEventSource && liveEventSource.readyState === EventSource.CLOSED) {
                disconnectLiveSync();
                liveReconnectTimer = window.setTimeout(connectLiveSync, 3000);
            }
        };
    }

    function disconnectLiveSync() {
        if (liveReconnectTimer) {
            window.clearTimeout(liveReconnectTimer);
            liveReconnectTimer = null;
        }
        if (liveEventSource) {
            liveEventSource.close();
            liveEventSource = null;
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        ensureAudioTargetControl();
        connectLiveSync();
    });
    window.addEventListener('beforeunload', disconnectLiveSync);

    window.ManagerAjax = {
        postJson: postJson,
        applyState: applyState,
        currentContext: currentContext,
        showMessage: showMessage,
        connectLiveSync: connectLiveSync,
        disconnectLiveSync: disconnectLiveSync,
        hasDedicatedAudioTarget: hasDedicatedAudioTarget,
        isThisDeviceAudioTarget: isThisDeviceAudioTarget,
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
