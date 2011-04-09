package sudoku;

public class SolveEmptyBoard {
    public static void main(String[] args) {
        Board.Builder builder = new Board.Builder();
        for (int n = 0; n < 81; n++) {
            builder.add(0);
        }
        Board board = builder.build();

        final Listener.Printer printer = new Listener.Printer(System.out);

        board.solve(new Listener() {
            @Override
            public boolean solution(byte[] cells) {
                printer.solution(cells);
                return false;
            }
        });
    }
}
