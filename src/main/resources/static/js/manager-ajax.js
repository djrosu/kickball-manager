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
        } else if (action.indexOf('/game/end-at-bat') >= 0) {
            endpoint = '/manager/api/game/end-at-bat';
            body.teamId = context.managedTeamId;
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
        const disabled = gameInProgress ? '' : ' disabled';
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
            + '<div class="lineup-player-name">' + name + '</div>'
            + '<div class="lineup-details">' + fullName + '</div>'
            + '<span class="current-batter-badge" style="' + badgeStyle + '">At Bat</span>'
            + '</td>'
            + '<td data-label="Runs">' + entry.runsScored + '</td>'
            + '<td class="mobile-actions-cell"><div class="lineup-actions">'
            + actionForm(prefix + '/lineup/' + entry.rosterEntryId + '/up', '↑', 'secondary order-button', false)
            + actionForm(prefix + '/lineup/' + entry.rosterEntryId + '/down', '↓', 'secondary order-button', false)
            + actionForm(prefix + '/runs/' + entry.rosterEntryId, '+ Run', '', !gameInProgress, disabledClass)
            + actionForm(prefix + '/runs/' + entry.rosterEntryId + '/remove', '- Run', 'secondary', !gameInProgress, disabledClass)
            + '<form action="' + prefix + '/roster/' + entry.rosterEntryId + '/remove" method="post"'
            + ' class="confirm-remove-player-form" data-player-name="' + escapeAttribute(entry.displayName || entry.fullName || 'this player') + '">'
            + '<button type="submit" class="danger">Remove</button></form>'
            + '</div></td></tr>';
    }

    function actionForm(action, label, buttonClass, isDisabled, formClass) {
        return '<form action="' + action + '" method="post" class="' + (formClass || '') + '">'
            + '<button type="submit" class="' + (buttonClass || '') + '"'
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
    function applyState(state, options) {
        options = options || {};
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

    window.ManagerAjax = {
        postJson: postJson,
        applyState: applyState,
        currentContext: currentContext,
        showMessage: showMessage
    };
})(window, document);
