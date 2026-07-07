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
            playerName: button.dataset.playerName || 'Player',
            artist: button.dataset.artist || '',
            title: button.dataset.title || '',
            introAudioUrl: button.dataset.introUrl || '',
            introPlayable: hasText(button.dataset.introUrl),
            audioUrl: button.dataset.songUrl || '',
            playable: hasText(button.dataset.songUrl)
        };
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');

        if (!token || !header) {
            return {};
        }

        return {
            [header.getAttribute('content')]: token.getAttribute('content')
        };
    }

    function wireAudioTestButtons() {
        document.querySelectorAll('[data-walkup-play-button]').forEach(function (button) {
            button.addEventListener('click', function () {
                const statusSelector = button.getAttribute('data-status-target');
                const statusElement = statusSelector ? document.querySelector(statusSelector) : null;
                playSequence(infoFromButton(button), statusElement).catch(function () {
                    // Error message is already displayed by playSequence.
                });
            });
        });
    }

    function wireStopButtons() {
        document.querySelectorAll('[data-walkup-stop-button]').forEach(function (button) {
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
        if (!form) {
            return;
        }

        form.addEventListener('submit', async function (event) {
            event.preventDefault();

            const statusElement = document.querySelector('#walkup-status');
            const currentBatterName = document.querySelector('#current-batter-name');
            const currentBatterSong = document.querySelector('#current-batter-song');
            const currentBattingTeam = document.querySelector('#current-batting-team');
            const currentInning = document.querySelector('#current-inning');
            const playCurrentButton = document.querySelector('#play-current-batter-audio');

            if (statusElement) {
                statusElement.textContent = 'Advancing to next batter...';
            }

            try {
                const response = await fetch(form.action, {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: csrfHeaders()
                });

                if (!response.ok) {
                    throw new Error('Unable to advance to the next batter.');
                }

                const info = await response.json();

                if (currentBatterName) {
                    currentBatterName.textContent = info.playerName || 'Current batter';
                }
                if (currentBatterSong) {
                    currentBatterSong.textContent = songLabel(info) || 'No song entered';
                }
                if (currentBattingTeam && info.battingTeamColor) {
                    currentBattingTeam.textContent = info.battingTeamColor;
                }
                if (currentInning && info.inning) {
                    currentInning.textContent = info.inning;
                }
                        if (playCurrentButton) {
                    updateButtonAudioData(playCurrentButton, info);
                    playCurrentButton.disabled = !(info.introPlayable || info.playable);
                }

                // Update the roster highlight before audio starts so the manager
                // can immediately see who is at bat while the intro/walk-up plays.
                updateCurrentBatterIndicator(info);
                await playSequence(info, statusElement);
            } catch (error) {
                if (statusElement) {
                    statusElement.textContent = error.message || 'Unable to advance to next batter.';
                }
            }
        });
    }

    function updateButtonAudioData(button, info) {
        button.dataset.playerId = info.playerId || '';
        button.dataset.rosterEntryId = info.rosterEntryId || '';
        button.dataset.playerName = info.playerName || '';
        button.dataset.artist = info.artist || '';
        button.dataset.title = info.title || '';
        button.dataset.introUrl = info.introPlayable ? info.introAudioUrl : '';
        button.dataset.songUrl = info.playable ? info.audioUrl : '';
    }

    /**
     * Updates the highlighted row in the roster table after AJAX Next Batter.
     * Without this, the text at the top of the page changes, but the lineup row
     * still visually marks the old batter until the whole page is reloaded.
     */
    /**
     * Updates the highlighted row in the roster table after AJAX Next Batter.
     *
     * We deliberately use several matching strategies. During active game play,
     * the AJAX response should contain the current rosterEntryId, which is the
     * most precise identifier. If a browser has stale markup or an older page
     * shape, we fall back to matching by team id + player id, then player id.
     */
    function updateCurrentBatterIndicator(info) {
        const rosterEntryIdText = valueAsText(info && info.rosterEntryId);
        const teamIdText = valueAsText(info && info.battingTeamId);
        const playerIdText = valueAsText(info && info.playerId);

        // Clear the old visual state and hide every At Bat badge.
        document.querySelectorAll('.lineup-row.current-batter').forEach(function (row) {
            row.classList.remove('current-batter');
            row.removeAttribute('aria-current');
        });
        document.querySelectorAll('.current-batter-badge').forEach(function (badge) {
            badge.style.display = 'none';
        });

        const nextRow = findCurrentBatterRow(rosterEntryIdText, teamIdText, playerIdText);
        if (!nextRow) {
            return;
        }

        nextRow.classList.add('current-batter');
        nextRow.setAttribute('aria-current', 'true');

        const badge = nextRow.querySelector('.current-batter-badge');
        if (badge) {
            badge.style.display = 'inline-block';
        }

        // Keep the current batter visible on smaller phone screens.
        nextRow.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function valueAsText(value) {
        return value === null || value === undefined ? '' : String(value);
    }

    function findCurrentBatterRow(rosterEntryIdText, teamIdText, playerIdText) {
        if (rosterEntryIdText) {
            const byRosterEntry = document.querySelector('[data-roster-entry-id="' + cssEscape(rosterEntryIdText) + '"]');
            if (byRosterEntry) {
                return byRosterEntry;
            }
        }

        if (teamIdText && playerIdText) {
            const byTeamAndPlayer = document.querySelector(
                '[data-team-id="' + cssEscape(teamIdText) + '"][data-player-id="' + cssEscape(playerIdText) + '"]'
            );
            if (byTeamAndPlayer) {
                return byTeamAndPlayer;
            }
        }

        if (playerIdText) {
            const byPlayer = document.querySelector('[data-player-id="' + cssEscape(playerIdText) + '"]');
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
        playSequence: playSequence,
        init: init
    };

    document.addEventListener('DOMContentLoaded', init);
})(window, document);
