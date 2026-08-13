/*
 * WalkupPlayer
 * ------------
 * Reusable audio and live-batter UI helper for the manager dashboard and
 * walk-up upload page.
 *
 * Browser note:
 * Mobile Safari/iPadOS generally requires audio playback to begin from a user
 * action. The manager's click on "Next Batter", "Play Current Batter Audio",
 * or a test button provides that user gesture. If a browser still blocks audio,
 * we show a status message and leave a manual play button available.
 */
(function (window, document) {
    'use strict';

    /*
     * Use one persistent HTMLAudioElement for all game audio.
     *
     * Reusing the same element improves mobile-browser reliability after the
     * manager explicitly enables this device as the shared audio target.
     * A session id prevents stale asynchronous sequences from starting another
     * clip after newer game audio has already begun.
     */
    const sharedAudioElement = new Audio();
    sharedAudioElement.preload = 'auto';

    let audioSessionId = 0;
    let cancelActivePlayback = null;

    function hasText(value) {
        return value !== null && value !== undefined && String(value).trim() !== '';
    }

    /**
     * Stops the current media request AND resolves the Promise currently waiting
     * for it. The latter is critical: pausing an Audio object alone does not
     * cancel an async intro -> song sequence.
     */
    function stop() {
        audioSessionId += 1;

        if (cancelActivePlayback) {
            cancelActivePlayback();
            cancelActivePlayback = null;
        }

        try {
            sharedAudioElement.pause();
            sharedAudioElement.currentTime = 0;
        } catch (ignored) {
            // Resetting an unloaded media element can throw on some browsers.
        }

        console.debug('[AUDIO] stopped; active session is now', audioSessionId);
    }

    /** Starts and returns a new logical audio-session id. */
    function beginSession() {
        stop();
        return audioSessionId;
    }

    /**
     * Plays one URL on the persistent media element.
     *
     * @return Promise<boolean> true for a natural end, false when cancelled by
     * a newer session.
     */
    function playUrl(url, sessionId) {
        return new Promise(function (resolve, reject) {
            if (!hasText(url)) {
                resolve(true);
                return;
            }

            if (sessionId !== audioSessionId) {
                resolve(false);
                return;
            }

            const separator = String(url).includes('?') ? '&' : '?';
            const playbackUrl = String(url).startsWith('/uploads/')
                ? String(url) + separator + 'v=' + Date.now()
                : String(url);

            let settled = false;

            function cleanup() {
                sharedAudioElement.removeEventListener('ended', onEnded);
                sharedAudioElement.removeEventListener('error', onError);
                if (cancelActivePlayback === cancelThisPlayback) {
                    cancelActivePlayback = null;
                }
            }

            function finish(value) {
                if (settled) {
                    return;
                }
                settled = true;
                cleanup();
                resolve(value);
            }

            function fail(error) {
                if (settled) {
                    return;
                }
                settled = true;
                cleanup();
                reject(error);
            }

            function onEnded() {
                finish(sessionId === audioSessionId);
            }

            function onError() {
                fail(new Error('Unable to play audio file: ' + url));
            }

            function cancelThisPlayback() {
                finish(false);
            }

            cancelActivePlayback = cancelThisPlayback;

            sharedAudioElement.addEventListener('ended', onEnded);
            sharedAudioElement.addEventListener('error', onError);
            sharedAudioElement.src = playbackUrl;
            sharedAudioElement.currentTime = 0;

            console.debug('[AUDIO] play', {
                sessionId: sessionId,
                url: url
            });

            const playPromise = sharedAudioElement.play();
            if (playPromise && typeof playPromise.catch === 'function') {
                playPromise.catch(fail);
            }
        });
    }

    /**
     * Primes the persistent media element from the manager's direct checkbox
     * gesture so later SSE-triggered playback is allowed on mobile browsers.
     *
     * The bundled unlock file is a real, very short silent MP3.
     */
    /**
     * Primes the persistent Audio element during the checkbox user gesture.
     *
     * <p>Important: this method does NOT wait for the silent MP3 to finish.
     * Mobile Chrome/iPadOS only needs play() to be accepted from the direct user
     * gesture. Waiting for an "ended" event created a first-use race where the
     * audio-target claim could remain blocked indefinitely.</p>
     *
     * <p>The returned promise always settles quickly. The caller is free to send
     * the server-side audio-target claim immediately instead of waiting on it.</p>
     */
    function unlockForRemotePlayback() {
        const sessionId = beginSession();

        console.debug('[AUDIO] priming persistent audio element');

        const separator = '/audio/audio-unlock.mp3'.includes('?') ? '&' : '?';
        sharedAudioElement.src =
            '/audio/audio-unlock.mp3' + separator + 'v=' + Date.now();
        sharedAudioElement.currentTime = 0;

        let playPromise;
        try {
            /*
             * This call occurs synchronously inside the checkbox change gesture.
             * That is the key action browsers use to grant later media playback.
             */
            playPromise = sharedAudioElement.play();
        } catch (error) {
            console.warn('[AUDIO] audio prime threw synchronously', error);
            return Promise.resolve(false);
        }

        const acceptedPromise =
            playPromise && typeof playPromise.then === 'function'
                ? playPromise.then(function () {
                    return true;
                }).catch(function (error) {
                    console.warn('[AUDIO] audio prime was rejected', error);
                    return false;
                })
                : Promise.resolve(true);

        /*
         * Never let media priming hold the UI hostage. Some mobile browsers can
         * leave a media promise unresolved while loading/suspending resources.
         */
        const timeoutPromise = new Promise(function (resolve) {
            window.setTimeout(function () {
                resolve(false);
            }, 750);
        });

        return Promise.race([acceptedPromise, timeoutPromise])
            .then(function (accepted) {
                /*
                 * Stop only our tiny prime clip. A newer real audio session may
                 * already have started by the time this asynchronous cleanup runs.
                 */
                if (sessionId === audioSessionId) {
                    try {
                        sharedAudioElement.pause();
                        sharedAudioElement.currentTime = 0;
                    } catch (ignored) {
                        // No action required.
                    }
                }

                console.debug('[AUDIO] persistent audio prime finished', {
                    accepted: accepted
                });

                return accepted;
            });
    }

    /**
     * Plays one ordinary MP3 as a new session. Used by the between-at-bat
     * playlist. A later batter command cancels it cleanly.
     */
    async function playStandalone(url, message, statusElement) {
        const sessionId = beginSession();

        if (!hasText(url)) {
            return false;
        }

        if (statusElement) {
            statusElement.textContent = message || 'Playing audio...';
        }

        try {
            const completed = await playUrl(url, sessionId);

            if (completed && sessionId === audioSessionId && statusElement) {
                statusElement.textContent = 'Audio finished.';
            }

            return completed;
        } catch (error) {
            if (sessionId === audioSessionId && statusElement) {
                statusElement.textContent =
                    'Audio was blocked or could not be played.';
            }
            throw error;
        }
    }

    function songLabel(info) {
        if (!info) {
            return '';
        }

        const artist = info.artist || '';
        const title = info.title || '';

        if (artist && title) {
            return artist + ' - ' + title;
        }
        return artist || title || '';
    }

    async function playSequence(info, statusElement) {
        const sessionId = beginSession();

        const sharedIntroUrl =
            info && info.sharedIntroPlayable
                ? info.sharedIntroAudioUrl
                : null;
        const introUrl =
            info && info.introPlayable
                ? info.introAudioUrl
                : null;
        const songUrl =
            info && info.playable
                ? info.audioUrl
                : null;
        const playerName =
            info && info.playerName ? info.playerName : 'current batter';
        const label = songLabel(info);

        if (!sharedIntroUrl && !introUrl && !songUrl) {
            if (statusElement) {
                statusElement.textContent =
                    'No intro or walk-up song uploaded for ' + playerName + '.';
            }
            return false;
        }

        if (statusElement) {
            statusElement.textContent =
                'Playing audio for ' + playerName
                + (label ? ' (' + label + ')' : '') + '...';
        }

        console.debug('[AUDIO] batter sequence started', {
            sessionId: sessionId,
            playerName: playerName
        });

        try {
            /*
             * Every stage returns false when a newer session supersedes it.
             * This is what prevents old between-at-bat or batter sequences from
             * waking up and playing over the current audio.
             */
            if (sharedIntroUrl
                    && !await playUrl(sharedIntroUrl, sessionId)) {
                return false;
            }

            if (introUrl
                    && !await playUrl(introUrl, sessionId)) {
                return false;
            }

            if (songUrl
                    && !await playUrl(songUrl, sessionId)) {
                return false;
            }

            if (sessionId === audioSessionId && statusElement) {
                statusElement.textContent =
                    'Finished audio for ' + playerName + '.';
            }

            return true;
        } catch (error) {
            if (sessionId === audioSessionId && statusElement) {
                statusElement.textContent =
                    'Audio was blocked or could not be played. '
                    + 'Tap Play Current Batter Audio.';
            }
            throw error;
        }
    }

    function infoFromButton(button) {
        return {
            playerId: button.dataset.playerId || null,
            rosterEntryId: button.dataset.rosterEntryId || null,
            battingTeamId: button.dataset.battingTeamId || null,
            battingTeamColor: button.dataset.battingTeamColor || null,
            playerName: button.dataset.playerName || 'Player',
            artist: button.dataset.artist || '',
            title: button.dataset.title || '',
            introAudioUrl: button.dataset.introUrl || '',
            introPlayable: hasText(button.dataset.introUrl),
            audioUrl: button.dataset.songUrl || '',
            playable: hasText(button.dataset.songUrl)
        };
    }

    /**
     * Applies current-at-bat information from the manager JSON API.
     *
     * The roster rows and scores are rendered by manager-ajax.js. This helper
     * owns the audio-specific fields and optionally starts the intro -> song
     * sequence when the action was Next Batter.
     */
    function applyDashboardState(state, shouldPlayAudio) {
        const info = state ? state.currentBatter : null;
        const statusElement = document.querySelector('#walkup-status');
        const currentBatterName = document.querySelector('#current-batter-name');
        const currentBatterSong = document.querySelector('#current-batter-song');
        const currentBattingTeam = document.querySelector('#current-batting-team');
        const currentInning = document.querySelector('#current-inning');
        const playCurrentButton = document.querySelector('#play-current-batter-audio');

        if (currentInning && state && state.inning != null) {
            currentInning.textContent = state.inning;
        }
        if (currentBattingTeam) {
            currentBattingTeam.textContent = state && state.currentBattingTeamColor
                ? state.currentBattingTeamColor
                : 'None';
        }
        if (currentBatterName) {
            currentBatterName.textContent = info && info.playerName ? info.playerName : 'None';
        }
        if (currentBatterSong) {
            currentBatterSong.textContent = songLabel(info) || 'No song entered';
        }
        const context = window.ManagerAjax && typeof window.ManagerAjax.currentContext === 'function'
            ? window.ManagerAjax.currentContext()
            : { view: '', managedTeamId: null };
        const currentTeamIsVisible = context.view !== 'team'
            || !context.managedTeamId
            || Number(state.currentBattingTeamId) === Number(context.managedTeamId);

        document.querySelectorAll('[data-team-live-actions]').forEach(function (actions) {
            actions.hidden = !currentTeamIsVisible;
        });
        document.querySelectorAll('[data-team-not-batting]').forEach(function (message) {
            message.hidden = currentTeamIsVisible;
        });

        if (playCurrentButton) {
            updateButtonAudioData(playCurrentButton, info || {});
            playCurrentButton.disabled = !(state && state.gameInProgress
                && currentTeamIsVisible
                && info && (info.introPlayable || info.playable));
        }

        // A Team Manager page only renders that manager's own roster. When the
        // other team is batting, there is intentionally no local row to mark.
        if (info && currentTeamIsVisible) {
            updateCurrentBatterIndicator(info, statusElement);
        }

        if (shouldPlayAudio && info && currentTeamIsVisible) {
            playSequence(info, statusElement).catch(function () {
                // playSequence already supplies a useful status message.
            });
        }
    }

    function wireAudioTestButtons() {
        document.querySelectorAll('[data-walkup-play-button]').forEach(function (button) {
            if (button.dataset.walkupPlayWired === 'true') {
                return;
            }
            button.dataset.walkupPlayWired = 'true';
            button.addEventListener('click', async function () {
                const statusSelector = button.getAttribute('data-status-target');
                const statusElement =
                    statusSelector ? document.querySelector(statusSelector) : null;

                /*
                 * The live-game "Play Current Batter Audio" button must honor a
                 * dedicated audio target exactly like Next Batter does. Upload-page
                 * test buttons intentionally remain local to the browser being used.
                 */
                const isCurrentBatterButton =
                    button.id === 'play-current-batter-audio';

                if (isCurrentBatterButton && window.ManagerAjax) {
                    if (statusElement) {
                        statusElement.textContent =
                            window.ManagerAjax.hasDedicatedAudioTarget()
                                ? 'Sending current batter audio to the selected audio device...'
                                : 'Selecting intro and preparing current batter audio...';
                    }

                    try {
                        const playLocally =
                            window.ManagerAjax.shouldPlayAudioLocallyForThisAction();

                        /*
                         * If this browser owns shared audio, ignore the server's
                         * equivalent SSE loopback and use the HTTP response
                         * directly. This removes the first-use race entirely.
                         */
                        if (playLocally
                                && window.ManagerAjax.hasDedicatedAudioTarget()) {
                            window.ManagerAjax.suppressNextTargetedAudioCommand();
                        }

                        const state =
                            await window.ManagerAjax.requestRoutedCurrentBatterAudio();

                        if (playLocally
                                && state
                                && state.currentBatter) {
                            await playSequence(
                                state.currentBatter,
                                statusElement);
                        }
                    } catch (error) {
                        const message =
                            error.message || 'Unable to play current batter audio.';
                        if (statusElement) {
                            statusElement.textContent = message;
                        }
                        window.ManagerAjax.showMessage(message, true);
                    }
                    return;
                }

                playSequence(infoFromButton(button), statusElement).catch(function () {
                    // Error message is already displayed by playSequence.
                });
            });
        });
    }

    function wireStopButtons() {
        document.querySelectorAll('[data-walkup-stop-button]').forEach(function (button) {
            if (button.dataset.walkupStopWired === 'true') {
                return;
            }
            button.dataset.walkupStopWired = 'true';
            button.addEventListener('click', function () {
                stop();
                const statusSelector = button.getAttribute('data-status-target');
                const statusElement = statusSelector ? document.querySelector(statusSelector) : null;
                if (statusElement) {
                    statusElement.textContent = 'Audio stopped.';
                }
            });
        });
    }

    /**
     * Wires both Next Batter and Previous Batter controls.
     *
     * <p>Both actions stop between-at-bat music on the server, update the
     * highlighted player, and play the selected batter's intro/walk-up audio.
     * Dedicated audio routing is honored automatically.</p>
     */
    function wireBatterNavigationButtons() {
        document.querySelectorAll(
            '[data-next-batter-form], [data-previous-batter-form]'
        ).forEach(function (form) {
            if (form.dataset.batterNavigationWired === 'true') {
                return;
            }
            form.dataset.batterNavigationWired = 'true';

            form.addEventListener('submit', async function (event) {
                event.preventDefault();

                const isPrevious =
                    form.hasAttribute('data-previous-batter-form');
                const directionLabel = isPrevious ? 'previous' : 'next';
                const endpoint = isPrevious
                    ? '/manager/api/game/previous-batter'
                    : '/manager/api/game/next-batter';

                const statusElement = document.querySelector('#walkup-status');
                if (statusElement) {
                    statusElement.textContent =
                        'Moving to ' + directionLabel + ' batter...';
                }

                if (!window.ManagerAjax) {
                    if (statusElement) {
                        statusElement.textContent =
                            'Manager API client is not available. Refresh the page and try again.';
                    }
                    return;
                }

                const context = window.ManagerAjax.currentContext();

                try {
                    const playLocally =
                        window.ManagerAjax.shouldPlayAudioLocallyForThisAction();

                    /*
                     * When this browser is the selected audio target, stop any
                     * field-change music immediately and suppress the server's
                     * loopback stop/audio events. We then play the new batter
                     * directly from the JSON response.
                     */
                    if (playLocally
                            && window.ManagerAjax.hasDedicatedAudioTarget()) {
                        stop();
                        window.ManagerAjax.suppressNextAudioStop();
                        window.ManagerAjax.suppressNextTargetedAudioCommand();
                    }

                    const state = await window.ManagerAjax.postJson(endpoint, {
                        gameWeekId: context.gameWeekId,
                        teamId: context.managedTeamId,
                        deviceId: window.ManagerAjax.audioDeviceId()
                    });

                    window.ManagerAjax.applyState(state, {
                        playAudio: playLocally
                    });
                } catch (error) {
                    const message = error.message
                        || 'Unable to move to the ' + directionLabel + ' batter.';
                    if (statusElement) {
                        statusElement.textContent = message;
                    }
                    window.ManagerAjax.showMessage(message, true);
                }
            });
        });
    }

    function updateButtonAudioData(button, info) {
        button.dataset.playerId = valueAsText(info && info.playerId);
        button.dataset.rosterEntryId = valueAsText(info && info.rosterEntryId);
        button.dataset.battingTeamId = valueAsText(info && info.battingTeamId);
        button.dataset.battingTeamColor = valueAsText(info && info.battingTeamColor);
        button.dataset.playerName = valueAsText(info && info.playerName);
        button.dataset.artist = valueAsText(info && info.artist);
        button.dataset.title = valueAsText(info && info.title);
        button.dataset.introUrl = info && info.introPlayable ? info.introAudioUrl : '';
        button.dataset.songUrl = info && info.playable ? info.audioUrl : '';
    }

    /**
     * Updates the highlighted row in the roster table after AJAX Next Batter.
     *
     * Important implementation detail:
     * We find the next row BEFORE clearing the old highlight. Earlier versions
     * cleared the old row first. If the AJAX response did not contain the exact
     * roster-entry id, the page ended up with no highlighted row at all. This
     * version leaves the old highlight in place if it cannot find the new row,
     * which is safer during a live game.
     */
    function updateCurrentBatterIndicator(info, statusElement) {
        const rosterEntryIdText = valueAsText(info && info.rosterEntryId);
        const teamIdText = valueAsText(info && info.battingTeamId);
        const playerIdText = valueAsText(info && info.playerId);

        const nextRow = findCurrentBatterRow(rosterEntryIdText, teamIdText, playerIdText);
        if (!nextRow) {
            if (statusElement) {
                statusElement.textContent = 'Advanced to ' + (info && info.playerName ? info.playerName : 'next batter')
                    + ', but could not find roster row. ids: rosterEntryId='
                    + rosterEntryIdText + ', teamId=' + teamIdText + ', playerId=' + playerIdText + '.';
            }
            return false;
        }

        // Clear the old visual state and hide every At Bat badge only after we
        // know the replacement row exists.
        document.querySelectorAll('.lineup-row.current-batter').forEach(function (row) {
            row.classList.remove('current-batter');
            row.removeAttribute('aria-current');
        });
        document.querySelectorAll('.current-batter-badge').forEach(function (badge) {
            badge.style.display = 'none';
        });

        nextRow.classList.add('current-batter');
        nextRow.setAttribute('aria-current', 'true');

        const badge = nextRow.querySelector('.current-batter-badge');
        if (badge) {
            badge.style.display = 'inline-block';
        }

        // Keep the current batter visible on smaller phone screens.
        nextRow.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        return true;
    }

    function valueAsText(value) {
        return value === null || value === undefined ? '' : String(value);
    }

    function findCurrentBatterRow(rosterEntryIdText, teamIdText, playerIdText) {
        if (rosterEntryIdText) {
            const byRosterEntry = document.querySelector('.lineup-row[data-roster-entry-id="' + cssEscape(rosterEntryIdText) + '"]');
            if (byRosterEntry) {
                return byRosterEntry;
            }
        }

        if (teamIdText && playerIdText) {
            const byTeamAndPlayer = document.querySelector(
                '.lineup-row[data-team-id="' + cssEscape(teamIdText) + '"][data-player-id="' + cssEscape(playerIdText) + '"]'
            );
            if (byTeamAndPlayer) {
                return byTeamAndPlayer;
            }
        }

        if (playerIdText) {
            const byPlayer = document.querySelector('.lineup-row[data-player-id="' + cssEscape(playerIdText) + '"]');
            if (byPlayer) {
                return byPlayer;
            }
        }

        return null;
    }

    /**
     * CSS.escape is not available in every older mobile browser. IDs here are
     * numeric, but this helper keeps the selector construction safe and portable.
     */
    function cssEscape(value) {
        if (window.CSS && typeof window.CSS.escape === 'function') {
            return window.CSS.escape(value);
        }
        return String(value).replace(/"/g, '\\"');
    }

    function wireRemoveConfirmations() {
        document.querySelectorAll('.confirm-remove-player-form').forEach(function (form) {
            if (form.dataset.removeConfirmWired === 'true') {
                return;
            }
            form.dataset.removeConfirmWired = 'true';
            form.addEventListener('submit', function (event) {
                const playerName = form.getAttribute('data-player-name') || 'this player';
                if (!confirm('Remove ' + playerName + ' from this team?')) {
                    event.preventDefault();
                }
            });
        });
    }

    function init() {
        wireAudioTestButtons();
        wireStopButtons();
        wireBatterNavigationButtons();
        wireRemoveConfirmations();
    }

    window.WalkupPlayer = {
        stop: stop,
        unlockForRemotePlayback: unlockForRemotePlayback,
        playStandalone: playStandalone,
        playSequence: playSequence,
        updateCurrentBatterIndicator: updateCurrentBatterIndicator,
        applyDashboardState: applyDashboardState,
        init: init
    };

    document.addEventListener('DOMContentLoaded', init);
})(window, document);
