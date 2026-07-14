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

    let currentAudio = null;

    /*
     * Audio ownership is managed separately by manager-ajax.js. This module
     * touches the browser media element only when a real intro or walk-up file
     * is ready to play. In particular, claiming audio ownership never attempts
     * to play an empty, silent, or synthetic media URL.
     */

    function hasText(value) {
        return value !== null && value !== undefined && String(value).trim() !== '';
    }

    function stop() {
        if (currentAudio) {
            currentAudio.pause();
            currentAudio.currentTime = 0;
            currentAudio = null;
        }
    }

    function playUrl(url) {
        return new Promise(function (resolve, reject) {
            if (!hasText(url)) {
                resolve();
                return;
            }

            const audio = new Audio(url);
            currentAudio = audio;

            audio.addEventListener('ended', function () {
                resolve();
            }, { once: true });

            audio.addEventListener('error', function () {
                reject(new Error('Unable to play audio file: ' + url));
            }, { once: true });

            const playPromise = audio.play();
            if (playPromise && typeof playPromise.catch === 'function') {
                playPromise.catch(function (error) {
                    reject(error);
                });
            }
        });
    }

    /**
     * Plays one ordinary MP3, replacing any currently playing walk-up or break
     * audio. Used for the between-at-bat playlist.
     */
    async function playStandalone(url, message, statusElement) {
        stop();

        if (!hasText(url)) {
            return;
        }

        if (statusElement) {
            statusElement.textContent = message || 'Playing audio...';
        }

        try {
            await playUrl(url);
            if (statusElement) {
                statusElement.textContent = 'Audio finished.';
            }
        } catch (error) {
            if (statusElement) {
                statusElement.textContent = 'Audio was blocked or could not be played.';
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
        stop();

        const introUrl = info && info.introPlayable ? info.introAudioUrl : null;
        const songUrl = info && info.playable ? info.audioUrl : null;
        const playerName = info && info.playerName ? info.playerName : 'current batter';
        const label = songLabel(info);

        if (!introUrl && !songUrl) {
            if (statusElement) {
                statusElement.textContent = 'No intro or walk-up song uploaded for ' + playerName + '.';
            }
            return;
        }

        if (statusElement) {
            statusElement.textContent = 'Playing audio for ' + playerName + (label ? ' (' + label + ')' : '') + '...';
        }

        try {
            // Intro always plays first when present. When it finishes, the
            // walk-up song starts automatically.
            if (introUrl) {
                await playUrl(introUrl);
            }
            if (songUrl) {
                await playUrl(songUrl);
            }

            if (statusElement) {
                statusElement.textContent = 'Finished audio for ' + playerName + '.';
            }
        } catch (error) {
            if (statusElement) {
                statusElement.textContent = 'Audio was blocked or could not be played. Tap Play Current Batter Audio.';
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

                if (isCurrentBatterButton
                        && window.ManagerAjax
                        && window.ManagerAjax.hasDedicatedAudioTarget()) {
                    if (statusElement) {
                        statusElement.textContent =
                            'Sending current batter audio to the selected audio device...';
                    }

                    try {
                        await window.ManagerAjax.requestRoutedCurrentBatterAudio();
                    } catch (error) {
                        const message =
                            error.message || 'Unable to route current batter audio.';
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

    function wireNextBatterButton() {
        const form = document.querySelector('[data-next-batter-form]');
        if (!form || form.dataset.nextBatterWired === 'true') {
            return;
        }
        form.dataset.nextBatterWired = 'true';

        form.addEventListener('submit', async function (event) {
            event.preventDefault();

            const statusElement = document.querySelector('#walkup-status');
            if (statusElement) {
                statusElement.textContent = 'Advancing to next batter...';
            }

            if (!window.ManagerAjax) {
                if (statusElement) {
                    statusElement.textContent = 'Manager API client is not available. Refresh the page and try again.';
                }
                return;
            }

            const context = window.ManagerAjax.currentContext();
            try {
                const state = await window.ManagerAjax.postJson('/manager/api/game/next-batter', {
                    gameWeekId: context.gameWeekId,
                    teamId: context.managedTeamId,
                    deviceId: window.ManagerAjax.audioDeviceId()
                });

                // Apply all server changes first, then begin audio while the
                // manager's click still qualifies as a browser user gesture.
                window.ManagerAjax.applyState(state, {
                    playAudio: !window.ManagerAjax.hasDedicatedAudioTarget()
                });
            } catch (error) {
                if (statusElement) {
                    statusElement.textContent = error.message || 'Unable to advance to next batter.';
                }
                window.ManagerAjax.showMessage(error.message || 'Unable to advance to next batter.', true);
            }
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
        wireNextBatterButton();
        wireRemoveConfirmations();
    }

    window.WalkupPlayer = {
        stop: stop,
        playStandalone: playStandalone,
        playSequence: playSequence,
        updateCurrentBatterIndicator: updateCurrentBatterIndicator,
        applyDashboardState: applyDashboardState,
        init: init
    };

    document.addEventListener('DOMContentLoaded', init);
})(window, document);
