package sudoku;

import java.util.ArrayList;
import java.util.concurrent.RecursiveAction;

import static java.lang.Integer.bitCount;
import static java.util.Arrays.copyOf;

/**
 * Sudoku board that is able to fill empty cells with matching values.
 */
public class Board {
    /**
     * Assists in building sudoku board.
     */
    public static class Builder {
        private final int[] cells = new int[CELLS];
        private int count;

        /**
         * Add board cell.
         *
         * @param value Cell value.
         * @return This builder instance.
         */
        public Builder add(int value) {
            if (count == CELLS) {
                throw new IllegalStateException("too much input");
            }
            if (value < 0 || value > 9) {
                throw new IllegalArgumentException("input out of range");
            }
            cells[count++] = value;
            return this;
        }

        /**
         * @return Sudoku board built from the specified data.
         */
        public Board build() {
            if (count < CELLS) {
                throw new IllegalStateException("insufficient input");
            }
            return new Board(cells);
        }
    }

    /**
     * Forks new actions when there is a need to split search.
     */
    private static class SolverAction extends RecursiveAction {
        private final Listener listener;
        private final int[] cells;
        private final int[] candidates;
        private int index;

        private SolverAction(Listener listener, int[] cells, int[] candidates, int index) {
            this.listener = listener;
            this.cells = cells;
            this.candidates = candidates;
            this.index = index;
        }

        @Override
        protected void compute() {
            solve(index);
        }

        private void solve(int index) {
            if (index == candidates.length) {
                // All candidates are filled in, so the solution is found!
                validateBoard(cells);
                listener.solution(cells);
            } else {
                int v = candidates[index];
                int vx = (v >> 10) & 15;
                int vy = (v >> 14) & 15;
                int vb = (v >> 18) & 15;
                int vs = v & 511;

                // Iterate over all candidates for a cell.
                int branches = bitCount(vs);
                if (branches > 1) {
                    // Search in parallel.
                    ArrayList<SolverAction> tasks = new ArrayList<SolverAction>(branches);

                    int n = 0;
                    while (vs > 0) {
                        if ((vs & 1) == 1) {
                            // Found the next candidate.
                            cells[vy * DIM + vx] = n + 1;

                            int[] nextCells;
                            int[] nextCandidates;
                            if (vs > 1) {
                                // Going to fork search, so make clone of the mutable lists.
                                nextCells = copyOf(cells, cells.length);
                                nextCandidates = copyOf(candidates, candidates.length);
                            } else {
                                // The last fork branch, share mutable lists to preserve memory.
                                nextCells = cells;
                                nextCandidates = candidates;
                            }

                            // Remove this candidate from the corresponding row, cell and block.
                            removeCandidate(nextCandidates, vx, vy, vb, n);

                            // Solve recursively in parallel thread.
                            tasks.add(new SolverAction(listener, nextCells, nextCandidates, index + 1));
                        }
                        vs >>= 1;
                        n++;
                    }

                    // Fork parallel solvers.
                    invokeAll(tasks);
                } else {
                    // Not forking so search recursively.
                    int n = 0;
                    while (vs > 0) {
                        if ((vs & 1) == 1) {
                            // Found the next candidate.
                            cells[vy * DIM + vx] = n + 1;

                            // Remove this candidate from the corresponding row, cell and block.
                            removeCandidate(candidates, vx, vy, vb, n);

                            // Solve recursively.
                            solve(index + 1);

                            // Undo move.
                            cells[vy * DIM + vx] = 0;

                            // Only one branch here.
                            break;
                        }
                        vs >>= 1;
                        n++;
                    }
                }
            }
        }
    }

    /**
     * Board width and height.
     */
    public static final int DIM = 9;
    /**
     * Number of cells in the board.
     */
    public static final int CELLS = DIM * DIM;
    /**
     * Board cell values in range 1-9, zero means empty cell.
     */
    private final int[] cells;

    private Board(int[] cells) {
        this.cells = cells;
    }

    /**
     * Solve sudoku.
     *
     * @param listener Listener to be notified of solutions.
     */
    public void solve(Listener listener) {
        // For every cell find set of candidate values.
        int[] candidates = findCandidates(cells);
        // Sort candidate values by set size, increasing.
        sortCandidates(candidates);
        // Solve recursively starting from the first candidate.
        solveSequentially(listener, candidates, 0);
    }

    /**
     * Create recursive action to solve Sudoku board in parallel
     * in a fork join pool.
     *
     * @param listener Listener to be notified of solutions.
     * @return A recursive action instance.
     */
    public RecursiveAction newSolverAction(Listener listener) {
        int[] candidates = findCandidates(cells);
        sortCandidates(candidates);
        return new SolverAction(listener, cells, candidates, 0);
    }


    /**
     * The algorithm!
     *
     * @param listener   A listener instance to be notified of found solutions.
     * @param candidates Array of candidate sets.
     * @param index      The index of candidate set to start search from.
     * @return Whether to keep searching or just stop.
     */
    private boolean solveSequentially(Listener listener, int[] candidates, int index) {
        if (index == candidates.length) {
            // All candidates are filled in, so the solution is found!
            validateBoard(cells);
            return listener.solution(cells);
        } else {
            int v = candidates[index];
            int vx = (v >> 10) & 15;
            int vy = (v >> 14) & 15;
            int vb = (v >> 18) & 15;
            int vs = v & 511;

            // Iterate over all candidates for a cell.
            int n = 0;
            while (vs > 0) {
                if ((vs & 1) == 1) {
                    // Found the next candidate.
                    cells[vy * DIM + vx] = n + 1;

                    int[] nextCandidates;
                    if (vs > 1) {
                        // Going to fork search, so make clone of the mutable candidates list.
                        nextCandidates = copyOf(candidates, candidates.length);
                    } else {
                        // The last fork branch, share mutable list to preserve memory.
                        nextCandidates = candidates;
                    }

                    // Remove this candidate from the corresponding row, cell and block.
                    removeCandidate(nextCandidates, vx, vy, vb, n);

                    // Solve recursively.
                    boolean more = solveSequentially(listener, nextCandidates, index + 1);

                    // Undo move.
                    cells[vy * DIM + vx] = 0;

                    if (!more) {
                        // Listener does not want new results,
                        // so stop searching.
                        return false;
                    }
                }
                vs >>= 1;
                n++;
            }

            return true;
        }
    }

    /**
     * Remove candidate values from cells on the same row, column
     * and in the same block.
     *
     * @param candidate Candidates array.
     * @param vx        Current cell X coordinate.
     * @param vy        Current cell Y coordinate.
     * @param vb        Current cell block number.
     * @param n         Candidates to remove.
     */
    private static void removeCandidate(int[] candidate, int vx, int vy, int vb, int n) {
        for (int i = 0; i < candidate.length; i++) {
            int w = candidate[i];
            int wx = (w >> 10) & 15;
            int wy = (w >> 14) & 15;
            int wb = (w >> 18) & 15;
            // See if other candidate set is on the same row, column or block.
            if (wx == vx || wy == vy || wb == vb) {
                // Remove candidate value from the other set.
                candidate[i] = w & ~(1 << n);
            }
        }
    }

    /**
     * Make sure the board is fully solved.
     *
     * @param cells Board cells.
     */
    private static void validateBoard(int[] cells) {
        for (int y = 0; y < DIM; y++) {
            for (int x = 0; x < DIM; x++) {
                int s = findCellCandidates(cells, x, y);
                if (s > 0) {
                    throw new IllegalStateException("empty cell");
                }
            }
        }
    }

    /**
     * For every empty board cell fill set of possible candidate values.
     * <p>Every array element is a record of fields encoded as a bit string:</p>
     * <pre>
     *   B   Y   X   BITSET      record field name
     * +---+---+---+--------+
     *   4   4   4     10        bit count
     * </pre>
     * <p>Where <code>B</code> is cell block number in range [1-9] minus 1,
     * <code>X</code> and <code>Y</code> are coordinates of the cell in
     * range [1-9] minus 1. <code>BITSET</code> is a bit mask where each bit
     * in position <code>P</code> designates candidate decimal number
     * <code>P + 1</code>. If, for example 3th bit is set, it means that 4
     * is a possible candidate for cell X,Y. There may be up to 9 candidates
     * for every empty cell, hence at most 9 bits set.</p>
     *
     * @param cells Board cells.
     * @return Array of candidate records.
     */
    private static int[] findCandidates(int[] cells) {
        int[] candidates = new int[CELLS];
        int length = 0;
        for (int y = 0; y < DIM; y++) {
            for (int x = 0; x < DIM; x++) {
                if (cells[y * DIM + x] == 0) {
                    int s = findCellCandidates(cells, x, y);
                    s = s // 10 bits for candidates bit set
                            | (x << 10) // 4 bits for x coordinate
                            | (y << 14) // 4 bits for y coordinate
                            | ((y / 3 * 3 + x / 3) << 18); // 4 bits for block number
                    candidates[length++] = s;
                }
            }
        }
        return copyOf(candidates, length);
    }

    /**
     * Find all possible values to put in the empty cell with
     * the specified coordinates.
     *
     * @param cells Board cells.
     * @param x     Cell X coordinate.
     * @param y     Cell Y coordinate.
     * @return Cell candidate values encoded as a bit set.
     * @throws IllegalStateException If board contains illegal values.
     */
    private static int findCellCandidates(int[] cells, int x, int y) {
        // scan row x
        int s1 = 0;
        for (int i = 0; i < DIM; i++) {
            int c = cells[y * DIM + i];
            if (c > 0) {
                int bit = 1 << (c - 1);
                if ((s1 & bit) != 0) {
                    throw new IllegalStateException(
                            String.format("illegal value %d at %d:%d", c, i + 1, y + 1));
                }
                s1 |= bit;
            }
        }
        // scan column y
        int s2 = 0;
        for (int i = 0; i < DIM; i++) {
            int c = cells[i * DIM + x];
            if (c > 0) {
                int bit = 1 << (c - 1);
                if ((s2 & bit) != 0) {
                    throw new IllegalStateException(
                            String.format("illegal value %d at %d:%d", c, x + 1, i + 1));
                }
                s2 |= bit;
            }
        }
        // scan block 3x3 containing point x,y
        int s3 = 0;
        for (int j = (x / 3) * 3; j < 3; j++) {
            for (int i = (y / 3) * 3; i < 3; i++) {
                int c = cells[j * DIM + i];
                if (c > 0) {
                    int bit = 1 << (c - 1);
                    if ((s3 & bit) != 0) {
                        throw new IllegalStateException(
                                String.format("illegal value %d at %d:%d", c, i + 1, j + 1));
                    }
                    s3 |= bit;
                }
            }
        }
        return ~(s1 | s2 | s3) & 511;
    }

    /**
     * Sort candidate sets so that cells with least number
     * of candidate values go first. In other words, start searching
     * from the cells that have fewest candidate values.
     *
     * @param candidates Array of candidate sets.
     */
    private static void sortCandidates(int[] candidates) {
        // Simple insertion sort by the number of candidates,
        // zeros go to end.
        for (int i = 1; i < candidates.length; i++) {
            for (int j = i; j > 0; j--) {
                int b = candidates[j];
                int cb = bitCount(b & 511);
                if (cb == 0) {
                    break;
                }
                int a = candidates[j - 1];
                int ca = bitCount(a & 511);
                if (ca == 0 || ca > cb) {
                    candidates[j - 1] = b;
                    candidates[j] = a;
                } else {
                    break;
                }
            }
        }
    }
}
